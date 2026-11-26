#!/usr/bin/env bash
# SessionStart hook (local and cloud sessions).
#  1. In a cloud session make sure the base branch is reachable: the scoped checks need the merge base,
#     and a fresh clone may not have fetched it (or may be shallow).
#  2. Record the working tree so the Stop gate knows what this session changed.
#  3. Print a one-line status that lands in the agent's context.
input=$(cat)

# Appends publicRepositories=true to the user-level gradle.properties unless the key is already set there
# (an explicit value is left as is, with a warning when the last one, which Gradle uses, is not true).
public_key='^[[:space:]]*publicRepositories([[:space:]]*[=:]|[[:space:]]|$)'
public_true='^[[:space:]]*publicRepositories([[:space:]]*[=:][[:space:]]*|[[:space:]]+)([Tt][Rr][Uu][Ee]|[Yy]|1)[[:space:]]*$'

enable_public_repositories() {
  [ -n "${GRADLE_USER_HOME:-$HOME}" ] || return 1
  local dir="${GRADLE_USER_HOME:-$HOME/.gradle}"
  local file="$dir/gradle.properties"
  if grep -Eq "$public_key" "$file" 2>/dev/null; then
    grep -E "$public_key" "$file" | tail -1 | grep -Eq "$public_true" || echo "[gauntlet] WARNING: $file sets publicRepositories to a value other than true; Gradle builds will use the corporate Nexus."
    return 0
  fi
  mkdir -p "$dir" 2>/dev/null || return 1
  if [ -s "$file" ] && [ -n "$(tail -c 1 "$file")" ]; then echo 2>/dev/null >> "$file" || return 1; fi
  echo 'publicRepositories=true' 2>/dev/null >> "$file"
}

if [ "$CLAUDE_CODE_REMOTE" = "true" ]; then
  enable_public_repositories || echo "[gauntlet] WARNING: could not write publicRepositories=true to the Gradle user home; builds will use the corporate Nexus."
fi

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0
# baseRef through the Stop hook's own reader of gauntlet.properties (`=`, `:` or blanks end the key, as for
# java.util.Properties in gauntlet.gradle and in CI): a reader of its own took only `=`, and `baseRef: origin/x`
# fetched and reported the wrong branch. -I keeps modules of the project directory out of the import, -B keeps
# bytecode out of gauntlet/.
base=$(python3 -I -B -c 'import sys; sys.path.insert(0, "gauntlet/hooks"); import stop_gate; print(stop_gate.properties().get("baseRef", ""))' 2>/dev/null)
base=${base:-origin/main}
remote=${base%%/*}
branch=${base#*/}

reachable() { git merge-base HEAD "$base" >/dev/null 2>&1; }

# An explicit refspec: a single-branch clone's configured refspec would not create origin/<base>.
fetch_base() { git fetch --quiet "$remote" "+refs/heads/$branch:refs/remotes/$remote/$branch" >/dev/null 2>&1; }

if [ "$CLAUDE_CODE_REMOTE" = "true" ] && [ "$remote" != "$base" ] && ! reachable; then
  fetch_base || true
  if ! reachable && [ "$(git rev-parse --is-shallow-repository 2>/dev/null)" = "true" ]; then
    git fetch --quiet --unshallow "$remote" >/dev/null 2>&1 || git fetch --quiet --deepen=1000 "$remote" >/dev/null 2>&1 || true
    fetch_base || true
  fi
fi

printf '%s' "$input" | python3 gauntlet/hooks/stop_gate.py --record-start

# Tests with a database or a broker run on Testcontainers and fail without Docker. In a cloud session the
# daemon is not running after the container starts (processes are not part of the environment snapshot), and
# `service docker start` fails on ulimit there, so start dockerd directly (as root or via passwordless sudo -n)
# and wait at most about 30 s for it. GAUNTLET_DOCKER=off skips the start (the hooks' selftest sets it);
# GAUNTLET_DOCKER=force enables the same start in non-Claude cloud VMs.
docker_up() { timeout 5 docker info >/dev/null 2>&1; }
docker_status=unavailable
if docker_up; then
  docker_status=up
elif pgrep -x dockerd >/dev/null 2>&1; then
  docker_status="starting (dockerd is running but not answering yet)"
elif { [ "$CLAUDE_CODE_REMOTE" = "true" ] || [ "${GAUNTLET_DOCKER:-}" = "force" ]; } \
    && [ "${GAUNTLET_DOCKER:-on}" != "off" ] \
    && command -v dockerd >/dev/null 2>&1 && command -v setsid >/dev/null 2>&1 \
    && { [ "$(id -u)" = 0 ] || sudo -n true 2>/dev/null; }; then
  # setsid + closed stdio: the daemon outlives the hook and does not hold its output open
  if [ "$(id -u)" = 0 ]; then
    setsid nohup dockerd --host=unix:///var/run/docker.sock </dev/null >>/tmp/dockerd.log 2>&1 &
  else
    setsid nohup sudo -n dockerd --host=unix:///var/run/docker.sock </dev/null >>/tmp/dockerd.log 2>&1 &
  fi
  daemon=$!
  docker_status="not started (see /tmp/dockerd.log)"
  deadline=$((SECONDS + 30))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if [ "$(id -u)" != 0 ] && [ -S /var/run/docker.sock ] && [ ! -w /var/run/docker.sock ]; then
      sudo -n chgrp "$(id -gn)" /var/run/docker.sock 2>/dev/null && sudo -n chmod 660 /var/run/docker.sock 2>/dev/null || true
    fi
    if docker_up; then docker_status=up; break; fi
    kill -0 "$daemon" 2>/dev/null || break
    sleep 1
  done
fi
if [ "$docker_status" = up ] && [ "${GAUNTLET_DOCKER:-on}" != "off" ] \
    && [ "$(timeout 5 docker info --format '{{.CgroupDriver}}' 2>/dev/null)" = "none" ]; then
  shim_sock="${GAUNTLET_DOCKER_SHIM_SOCK:-/tmp/docker-pause-shim.sock}"
  shim_alive() { [ -S "$shim_sock" ] && timeout 3 docker --host "unix://$shim_sock" info >/dev/null 2>&1; }
  if ! shim_alive && command -v setsid >/dev/null 2>&1; then
    rm -f "$shim_sock" 2>/dev/null || true
    setsid nohup python3 -I -B gauntlet/hooks/docker_pause_shim.py "$shim_sock" </dev/null >>/tmp/docker-pause-shim.log 2>&1 &
    for _ in 1 2 3 4 5 6; do shim_alive && break; sleep 0.5; done
  fi
  shim_alive && docker_status="up (pause-shim: $shim_sock)"
fi

if reachable; then
  echo "[gauntlet] active - scope: changed vs $base; guard: ${GAUNTLET_GUARD:-on}; stop hook: ${GAUNTLET_STOP_HOOK:-on}; docker: $docker_status. Process: gauntlet/CLAUDE.md"
else
  echo "[gauntlet] WARNING: cannot find the merge base of HEAD and $base, so the scoped checks will fail. Run 'git fetch $remote +refs/heads/$branch:refs/remotes/$remote/$branch' (after 'git fetch --unshallow $remote' in a shallow clone) or use -Pgauntlet.scope=all."
fi
exit 0
