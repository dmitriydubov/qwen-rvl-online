#!/usr/bin/env python3
"""Unix-socket proxy in front of /var/run/docker.sock for DinD environments with CgroupDriver=none.

When a containerised session runs dockerd without the cgroup freezer controller (`docker info --format
'{{.CgroupDriver}}'` -> `none`), `POST /containers/<id>/pause` and `/unpause` fail with `500 freezer not
supported`, breaking Testcontainers scenarios that freeze a dependency (`pauseContainerCmd`) to exercise
read timeouts. Start this shim outside the repository (`/tmp/docker-pause-shim.sock`), restart any
existing Gradle daemon (`./gradlew --stop`), and run builds with `DOCKER_HOST=unix:///tmp/docker-pause-shim.sock`:

  nohup python3 -I -B gauntlet/hooks/docker_pause_shim.py /tmp/docker-pause-shim.sock >/tmp/docker-pause-shim.log 2>&1 &

Every Docker Engine API request is forwarded raw to `/var/run/docker.sock` (non-Upgrade HTTP/1.1 requests
receive `Connection: close` so pooled clients open a fresh stream per request; `Upgrade: tcp` streams half-close
via `write_eof()` so `docker exec` and `attach` preserve output); `POST .../containers/<id>/(pause|unpause)`
sends `SIGSTOP` / `SIGCONT` to the container's host PIDs (`docker top <id> -eo pid`) and answers `204 No Content`.
"""
import asyncio
import os
import re
import signal
import subprocess
import sys

sys.dont_write_bytecode = True

UPSTREAM = os.environ.get("DOCKER_UPSTREAM_SOCK", "/var/run/docker.sock")
DOCKER_BIN = os.environ.get("DOCKER_CLI_BIN", "docker")
LISTEN = sys.argv[1] if len(sys.argv) > 1 else "/tmp/docker-pause-shim.sock"
PAUSE_RE = re.compile(
    rb"^POST /(?:v[\d.]+/)?containers/([a-zA-Z0-9][a-zA-Z0-9_.-]*)/(pause|unpause)(?:\?[^\s]*)? HTTP/1\.[01]\r\n"
)


def signal_container(cid, sig):
    out = subprocess.check_output(
        [DOCKER_BIN, "--host", f"unix://{UPSTREAM}", "top", cid, "-eo", "pid"], text=True
    )
    pids = [int(line.strip()) for line in out.splitlines()[1:] if line.strip().isdigit()]
    if not pids:
        raise RuntimeError(f"no PIDs found for container {cid}")
    signaled = 0
    for pid in pids:
        try:
            os.kill(pid, sig)
            signaled += 1
        except ProcessLookupError:
            continue
        except PermissionError:
            subprocess.check_call(["sudo", "-n", "kill", f"-{int(sig)}", str(pid)])
            signaled += 1
    if signaled == 0:
        raise RuntimeError(f"all PIDs exited before signal for container {cid}")


async def pipe(reader, writer):
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
        if writer.can_write_eof():
            writer.write_eof()
    except Exception:
        pass


async def handle_client(creader, cwriter):
    try:
        head = await creader.readuntil(b"\r\n\r\n")
    except Exception:
        cwriter.close()
        return
    m = PAUSE_RE.match(head)
    if m:
        cid, action = m.group(1).decode(), m.group(2).decode()
        try:
            await asyncio.to_thread(signal_container, cid, signal.SIGSTOP if action == "pause" else signal.SIGCONT)
            cwriter.write(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")
        except Exception as exc:
            body = f'{{"message":"shim {action} failed: {exc}"}}\n'.encode()
            cwriter.write(
                b"HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json\r\n"
                + f"Content-Length: {len(body)}\r\nConnection: close\r\n\r\n".encode()
                + body
            )
        await cwriter.drain()
        cwriter.close()
        return
    if not re.search(rb"\r\nUpgrade:", head, re.I):
        head = re.sub(rb"\r\nConnection:[^\r\n]*", b"", head, flags=re.I)
        head = head[:-2] + b"Connection: close\r\n\r\n"
    ureader, uwriter = await asyncio.open_unix_connection(UPSTREAM)
    try:
        uwriter.write(head)
        await uwriter.drain()
        await asyncio.gather(pipe(creader, uwriter), pipe(ureader, cwriter))
    finally:
        uwriter.close()
        cwriter.close()


async def main():
    if os.path.exists(LISTEN):
        os.unlink(LISTEN)
    server = await asyncio.start_unix_server(handle_client, path=LISTEN)
    os.chmod(LISTEN, 0o660)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
