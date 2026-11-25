#!/usr/bin/env python3
"""Stop / SubagentStop gate for the gauntlet (Claude Code hook).

An agent may not finish while the gauntlet is red.

  stop_gate.py --record-start                              # SessionStart: remember the working tree
  stop_gate.py --task gauntletFast [--max-attempts 3]      # Stop / SubagentStop
  stop_gate.py --verify-ran gauntlet --log <gradle output>  # CI: did Gradle run every gate task?

The gate runs only when the content of the working tree differs from what it was when the session
started (the developer's pre-existing uncommitted work does not count, and committing it changes
nothing) and that exact content has not been verified before - so questions and read-only turns cost
nothing, and a red result is not re-run until something changes. A turn that changed nothing but
uncommitted feature files never runs the gate: the draft specification waits for the human, not for the
gauntlet; once the feature files are committed (`spec:`), the full gate runs so the session cannot stop
right after freezing the spec without implementing it. While uncommitted feature changes wait for the
human's approval in the chat, the acceptance tests are left out of the run (the agent must be able to
stop and ask), but every other check still runs. On red it exits 2 and feeds the failing lines back to
the agent, which keeps working. After --max-attempts consecutive red stops it lets the agent stop and
warns instead, so a session can never loop forever.

Humans can switch it off for a session:  GAUNTLET_STOP_HOOK=off (environment variable at start-up)
Extra Gradle arguments (e.g. --offline):  GAUNTLET_GRADLE_ARGS="--offline"
"""
import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import time

# buildSrc is build logic too: IntegrityCheck scans it, so a change there must reach the gate, not only CI
BUILD_FILES = ["src", "gradle", "buildSrc", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
               "gradle.properties"]
PROPERTIES = "gauntlet/config/gauntlet.properties"
DEFAULT_FEATURES = "src/acceptanceTest/resources/features"
SPEC_DEPENDENT_TASKS = ["acceptanceTest", "acceptanceCheck"]
# "module" in gauntlet.properties: the Gradle build directories inside a monorepo, comma-separated
# ("" = one build at the repository root). Gradle runs in each changed module; every path below is
# relative to the repository root.
MODULES = []
WATCHED = list(BUILD_FILES) + ["gauntlet"]
INPUT_KEYS = ["acceptance.inputs", "test.inputs"]   # files the tests read outside the source sets
INPUTS = []          # those files, relative to the repository root: watched even when missing
LOCK_WAIT_S = 1500
REPORT_DIRS = ["build/test-results", "build/reports"]   # newer files here = the agent ran Gradle itself
INTERESTING = re.compile(
    r"^(CRAP FAIL|DUPLICATE|DRY gate: FAILED|INTEGRITY|"
    r"MUTANT (SURVIVED|ERROR)|SURVIVED|NO_COVERAGE|RUN_ERROR|MEMORY_ERROR|NON_VIABLE|mutationGate: |"
    r"\s+\S+#\S+ \w+Mutator\w*$|ACCEPTANCE (SKIPPED|F2|ERROR)|WARNING .*mutation sites|"
    r"\[ERROR\]|\[ant:checkstyle\]|> Task .*FAILED|.*\bFAILED$|Execution failed for task|"
    r".*[A-Za-z](Exception|Error)(:|\s|$)|.*expected:?\s|Caused by|Architecture Violation|Rule '|"
    r"\s+at [a-z][\w.$]*\(\w+Test\.java:\d+\)|.*tests? completed, \d+ failed|gauntlet: )")
NOISE = re.compile(r"^(Picked up (JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS)|"
                   r"> Task .*(UP-TO-DATE|NO-SOURCE|SKIPPED)$|\s*$)")
CONTINUATION_MARGIN_S = 10 * 60   # added to the total run budget (modules * timeout): a slow Gradle run must not reset the counter
# What a verdict of the gate rests on: the task the hook (or CI) runs and every gauntlet task under it, as
# gauntlet/gauntlet.gradle wires them. Gradle prints "> Task <path>" (--console=plain, lifecycle log level) for each
# task it ran, found up to date or skipped, and nothing for a task the build left out of its task graph - a build
# script that adds it to gradle.startParameter.excludedTaskNames, a build that no longer applies the kit. The exit
# code alone believed such a build: one line in build.gradle left out every gate and Gradle still printed BUILD
# SUCCESSFUL.
GATE_TASKS = {
    "gauntletFast": ["gauntletScope", "integrityCheck", "checkstyleGate", "pmdGate", "test", "archTest", "testCensus",
                     "acceptanceTest", "acceptanceCheck", "jacocoTestReport", "crapGate", "cpdReport", "dryGate"],
    "mutationGate": ["gauntletScope", "pitest", "pitSummary"],
    "gherkinMutation": ["gauntletScope", "acceptanceTest", "gherkinMutants", "gherkinMutantsRun"],
    "gauntlet": ["gauntletFast", "mutationGate", "gherkinMutation"],
}
# "> Task :core:integrityCheck UP-TO-DATE": the name after the last ':' of the path, and the outcome if there is one
TASK_LINE = re.compile(r"^> Task (?:\S*:)?([^\s:]+)(?:[ \t]+([A-Z][A-Z-]*))?[ \t]*\r?$", re.M)
# A gate the kit skips itself (no feature file, nothing in scope) says so: gauntlet.gradle, skippedByTheKit
KIT_SKIP_LINE = re.compile(r"^gauntlet: (?:\S*:)?([^\s:]+) skipped by the kit - ", re.M)
# Gates with no onlyIf of the kit's own and no outputs: Gradle executes them on every run. SKIPPED, NO-SOURCE,
# UP-TO-DATE or FROM-CACHE there means the build switched them off - tasks.named('integrityCheck') { enabled = false }
# after apply, an onlyIf of its own, an empty input that skips, outputs that are up to date - and the skipped
# integrityCheck cannot name the line that skipped it.
MUST_EXECUTE = {"gauntletScope", "integrityCheck", "testCensus", "acceptanceCheck", "crapGate", "dryGate",
                "gherkinMutants", "gherkinMutation"}
# Gates the kit itself skips when there is nothing to judge: SKIPPED counts as run only with the kit's own line
KIT_SKIPS = {"checkstyleGate", "pmdGate", "cpdReport", "acceptanceTest", "pitest", "pitSummary", "gherkinMutantsRun"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--record-start", action="store_true")
    ap.add_argument("--task", default="gauntletFast")
    ap.add_argument("--max-attempts", type=int, default=3)
    ap.add_argument("--timeout", type=int, default=1500)
    ap.add_argument("--verify-ran", metavar="TASK", help="judge the Gradle output in --log: did it run every gate task of TASK")
    ap.add_argument("--log", help="the Gradle output that --verify-ran judges")
    opts = ap.parse_args()
    if opts.verify_ran:
        return verify_ran(opts.verify_ran, opts.log)
    try:
        event = json.load(sys.stdin)
    except Exception:
        event = {}
    project = os.environ.get("CLAUDE_PROJECT_DIR") or event.get("cwd") or os.getcwd()
    os.chdir(project)
    configure_module()
    state = state_dir()
    if state is None:
        # The gate sees what changed through git and counts its attempts in .git/gauntlet-hook: without them it
        # cannot run, and it must not hold the agent with a count it cannot keep. A silent 0 here read as green.
        if not opts.record_start and os.environ.get("GAUNTLET_STOP_HOOK", "").lower() != "off":
            print(json.dumps({"systemMessage":
                              f"[gauntlet] {opts.task} did NOT run: {project} is not inside a git repository, or git "
                              f"cannot keep the hook's state there (.git/gauntlet-hook). Nothing was checked - say so "
                              f"in your report."}))
        return 0
    session = safe(event.get("session_id", "unknown"))
    if opts.record_start:
        start_file = os.path.join(state, session + ".start")
        if not os.path.exists(start_file):
            write(start_file, json.dumps({"head": git("rev-parse", "HEAD"), "tree": tree_fingerprint(),
                                          "dirty": dirty_files()}))
        return 0
    if os.environ.get("GAUNTLET_STOP_HOOK", "").lower() == "off":
        return 0
    return gate(opts, event, state, session)


def gate(opts, event, state, session):
    start_file = os.path.join(state, session + ".start")
    try:
        start = json.loads(read(start_file))
    except ValueError:
        # SessionStart did not record a baseline: judge by uncommitted changes against HEAD instead
        start = {"head": git("rev-parse", "HEAD"), "tree": None}
        write(start_file, json.dumps(start))
    tree = tree_fingerprint()
    if tree == start.get("tree"):
        return 0  # nothing changed in this session
    changed = changed_paths(start.get("head", ""))
    # the developer's own uncommitted work from before the session is not the agent's change (unless touched since)
    baseline = start.get("dirty") or {}
    changed.update(baseline)  # a pre-existing dirty path restored to HEAD is still a change made in this session
    changed = {p for p in changed if baseline.get(p) != file_hash(p)}
    if not changed:
        return 0
    features = features_dirs()
    pending = specs_pending_approval(features)
    if pending and all(any(("/" + p).startswith("/" + f + "/") for f in features) for p in changed):
        return 0  # only an uncommitted spec draft changed: it waits for the human's approval, not for the gauntlet
    affected = affected_modules(changed)
    if not affected:
        return 0  # nothing inside a guarded build changed
    excluded = SPEC_DEPENDENT_TASKS if pending else []
    key = session + "-" + safe(event.get("agent_id") or "main")
    fingerprint = hashlib.sha256((opts.task + ("|pending" if pending else "") + "|".join(affected) + tree).encode()).hexdigest()
    green_file = os.path.join(state, "last-green-" + safe(opts.task))
    # The last-stop mark (code tree without the spec | pending/approved | ok/red) remembers the state in
    # which this agent was last allowed to stop. Same code since then = this turn changed only an uncommitted
    # spec draft (or nothing): the question to the human must not wait for Gradle. Once the spec is committed
    # (not pending), any unverified tree - including a spec committed in a single turn - must run the gate;
    # the last stop released a RED result - remind the agent, and re-run when it ran Gradle itself since
    # (newer test reports) unless a spec draft is waiting for the human, whose question goes first.
    code_tree = tree_fingerprint(exclude=features)
    state_tag = "pending" if pending else "approved"
    stop_mark = os.path.join(state, key + ".last-stop")
    last = read(stop_mark).split("|")
    if len(last) == 3 and last[0] == code_tree and not (last[1] == "pending" and not pending):
        if last[2] == "ok" and (pending or read(green_file) == fingerprint):
            write(stop_mark, f"{code_tree}|{state_tag}|ok")
            return 0
        if last[2] == "red" and (pending or not newer_than(mtime(stop_mark), REPORT_DIRS)):
            write(stop_mark, f"{code_tree}|{state_tag}|red")
            print(json.dumps({"systemMessage":
                              f"[gauntlet] reminder: {opts.task} was still RED when you were last allowed to stop and the "
                              f"code has not changed since. The work is NOT done - say so in your report."}))
            return 0
    if read(green_file) == fingerprint:
        write(stop_mark, f"{code_tree}|{state_tag}|ok")
        return 0
    attempts_file = os.path.join(state, key + ".attempts")
    red_file = os.path.join(state, "last-red-" + safe(opts.task))
    logs = [log_file(m, opts.task) for m in affected]
    # Judge each module's cache against its OWN reports: a recent Gradle run in module B must not be
    # compared with module A's log (the old min(logs) vs all REPORT_DIRS conflated the two modules).
    # isfile: a log path that is a directory reads as "" - a cached red without its causes
    cached = read(red_file) == fingerprint and all(os.path.isfile(l) for l in logs) \
        and not any(newer_than(mtime(log_file(m, opts.task)), module_report_dirs(m)) for m in affected)
    if cached:
        code, output = 1, "\n".join(read(l) for l in logs)   # nothing changed since the last red run: no re-run
    else:
        code, output = run_all(affected, opts.task, excluded, opts.timeout)
    log_path = ", ".join(logs)
    note = (" Acceptance tests were left out: the changed feature files wait for the human's approval "
            "in the chat (commit them only after the human said yes).") if pending else ""
    if code == 0:
        write(green_file, fingerprint)
        write(stop_mark, f"{code_tree}|{state_tag}|ok")
        remove(attempts_file)
        remove(red_file)
        if pending:
            print(json.dumps({"systemMessage": f"[gauntlet] {opts.task} is green.{note}"}))
        return 0
    write(red_file, fingerprint)
    attempts = next_attempt(attempts_file, event, opts.timeout, len(affected))
    if attempts > opts.max_attempts:
        remove(attempts_file)
        write(stop_mark, f"{code_tree}|{state_tag}|red")
        print(json.dumps({"systemMessage":
                          f"[gauntlet] {opts.task} is still RED after {opts.max_attempts} attempts - stopping anyway. "
                          f"The work is NOT done: say so in your report, with the causes; see {log_path}.{note}"}))
        return 0
    sys.stderr.write(
        f"[gauntlet] ./gradlew {opts.task} is RED (attempt {attempts}/{opts.max_attempts}). You cannot finish "
        f"yet: fix the causes below. Do not weaken tests, specs or checks.{note}\n\n{summarize(output)}\n\n"
        f"Full log: {log_path}\n")
    return 2


def uncommitted_paths():
    paths = set()
    status = git_bytes("status", "--porcelain", "--untracked-files=all", "--", *watched()).decode("utf-8", "replace")
    for line in status.splitlines():  # "XY path", relative to the repository root - do not strip: X may be a space
        path = line[3:].strip()
        if " -> " in path:            # a rename "R  old -> new": both ends changed, either may sit in a guarded build
            paths.update(part.strip().strip('"') for part in path.split(" -> "))
        else:
            paths.add(path.strip('"'))
    return {p for p in paths if p}


def dirty_files():
    """Uncommitted files at session start with their content hash."""
    return {p: file_hash(p) for p in uncommitted_paths()}


def file_hash(path):
    h = hashlib.sha256()
    try:
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 16), b""):
                h.update(chunk)
    except OSError:
        return "<missing>"
    return h.hexdigest()


def changed_paths(start_head):
    """Paths changed in this session: uncommitted changes plus commits made since the session started."""
    paths = uncommitted_paths()
    if start_head and git("rev-parse", "HEAD") != start_head:
        # --name-status -M reports a rename/copy as "R100\told\tnew": a committed move across modules
        # touches BOTH ends, so the source module's build must run too, not only the destination's.
        # Pass WATCHED unfiltered - a committed deletion is gone from disk/index/HEAD, and dropping it
        # from the pathspec would hide the deletion and skip the module's build.
        for line in git("diff", "--name-status", "-M", start_head, "HEAD", "--", *(WATCHED + INPUTS)).splitlines():
            paths.update(f.strip('"') for f in line.split("\t")[1:] if f.strip())
    return {p for p in paths if p}


def next_attempt(attempts_file, event, timeout, modules=1):
    previous, stamp = 0, 0.0
    raw = read(attempts_file)
    if raw:
        try:
            previous, stamp = int(raw.split()[0]), float(raw.split()[1])
        except (ValueError, IndexError):
            previous = 0
    # Stop: stop_hook_active is set by Claude Code after a blocked stop. SubagentStop: the same field only says
    # whether the subagent has Stop hooks of its own (hooks docs), so its retries are told apart by time.
    # The window must outlast the WHOLE run: run_all runs the task in every affected module sequentially, each
    # up to `timeout`, so the budget is modules * timeout. A run slower than the window would otherwise look
    # like a fresh start and reset the counter to 1 forever (a slow two-module mutationGate must not loop).
    if event.get("hook_event_name") == "Stop" or ("stop_hook_active" in event
                                                  and event.get("hook_event_name") != "SubagentStop"):
        continuing = bool(event.get("stop_hook_active"))
    else:
        continuing = time.time() - stamp < timeout * max(1, modules) + CONTINUATION_MARGIN_S
    attempts = previous + 1 if continuing else 1
    write(attempts_file, f"{attempts} {time.time()}")
    return attempts


def log_file(module, task):
    return os.path.join(module, "build", "gauntlet", f"{task}-hook.log")


def run_all(modules, task, excluded, timeout):
    """Runs the task in every affected module; red if any module is red."""
    worst, outputs = 0, []
    for module in modules:
        code, _, output = run_gradle(task, excluded, timeout, module)
        if code != 0:
            worst = max(worst, code if code > 0 else 1)   # a signal-killed run reports a negative code: red, not green
        outputs.append((f"=== {module} ===\n" if module != "." else "") + output)
    return worst, "\n".join(outputs)


def gradle_env():
    """The build picks public repositories only when told to; a cloud session is outside the corporate network."""
    env = dict(os.environ)
    if env.get("CLAUDE_CODE_REMOTE") == "true":
        env.setdefault("ORG_GRADLE_PROJECT_publicRepositories", "true")
    shim_sock = env.get("GAUNTLET_DOCKER_SHIM_SOCK", "/tmp/docker-pause-shim.sock")
    if not env.get("DOCKER_HOST") and shim_sock:
        try:
            if stat.S_ISSOCK(os.stat(shim_sock).st_mode):
                env["DOCKER_HOST"] = f"unix://{shim_sock}"
        except OSError:
            pass
    return env


def run_gradle(task, excluded, timeout, module="."):
    gradle = "./gradlew" if os.access(os.path.join(module, "gradlew"), os.X_OK) else "gradle"
    lock_wait = max(1, min(LOCK_WAIT_S, timeout // 2))
    cmd = [gradle, task, "--continue", "--console=plain", f"-Pgauntlet.lockWaitSeconds={lock_wait}"]
    for name in excluded:
        cmd += ["-x", name]
    cmd += os.environ.get("GAUNTLET_GRADLE_ARGS", "").split()
    log_path = log_file(module, task)
    # Every failure below is a red run, never an exception: a hook that dies exits 1, and Claude Code lets the
    # agent stop on that. A red run goes through the attempts counter, so the agent is never held for ever either.
    try:
        os.makedirs(os.path.dirname(log_path), exist_ok=True)
        # bytes, decoded with replacement: one byte that is not UTF-8 must not kill the hook (text=True would)
        proc = subprocess.run(cmd, cwd=module, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout,
                              env=gradle_env())
        code, output = proc.returncode, proc.stdout.decode("utf-8", "replace")
    except subprocess.TimeoutExpired as e:
        code = 124
        # keep the partial output: the agent needs to see how far the run got before the timeout
        partial = e.stdout or b""
        output = partial.decode("utf-8", "replace") if isinstance(partial, bytes) else partial
        output += f"\nTIMEOUT: {' '.join(cmd)} did not finish within {timeout}s"
    except FileNotFoundError:
        code, output = 127, f"Cannot run {gradle}: not found"
    except OSError as e:
        # gradlew is a directory or not executable, build/gauntlet is a file, the module path is a file
        code, output = 126, f"gauntlet: cannot run {gradle} {task} in {module}: {e}"
    missing = not_run(task, output, excluded) if code == 0 else []
    if missing:
        code, output = 1, output + "\n" + not_run_message(task, missing)
    try:
        write(log_path, output)
    except OSError as e:
        output += f"\ngauntlet: cannot write the hook log {log_path}: {e}"
    return code, log_path, output


def summarize(output, limit=12000):
    lines = [l for l in output.splitlines() if not NOISE.match(l)]
    picked, seen = [], set()
    for line in lines:
        if INTERESTING.match(line) and line not in seen:
            seen.add(line)
            picked.append(line)
    tail = [l for l in lines[-30:] if l not in seen]
    text = "\n".join(picked[:150]) + "\n\n--- end of the Gradle output ---\n" + "\n".join(tail)
    return text if len(text) <= limit else text[:limit] + "\n[...truncated]"


def gate_tasks(task):
    """The task and every gauntlet task under it (GATE_TASKS), each once. A task path (:app:gauntletFast) is its name."""
    names = [task.rsplit(":", 1)[-1]]
    for name in names:                      # the list grows while it is read: a breadth-first walk
        names += [n for n in GATE_TASKS.get(name, []) if n not in names]
    return names


def not_run(task, output, excluded=()):
    """The gate tasks of `task` that Gradle did not run, except those the hook left out itself: no "> Task" line, or one
    whose outcome says the build switched the gate off (MUST_EXECUTE, KIT_SKIPS)."""
    kit_skipped = set(KIT_SKIP_LINE.findall(output))
    ran = set()
    for name, outcome in TASK_LINE.findall(output):
        if name in MUST_EXECUTE and outcome:
            continue
        if name in KIT_SKIPS and outcome == "SKIPPED" and name not in kit_skipped:
            continue
        ran.add(name)
    return [n for n in gate_tasks(task) if n not in ran and n not in excluded]


def not_run_message(task, missing):
    them = "it" if len(missing) == 1 else "them"
    return (f"gauntlet: Gradle finished {task} without running {', '.join(missing)} - no \"> Task\" line shows "
            f"{them} executed. The build left {them} out (gradle.startParameter.excludedTaskNames in a build script, -x), "
            f"skipped {them} (enabled = false, an onlyIf of its own: SKIPPED without the kit's own reason, NO-SOURCE, "
            f"UP-TO-DATE of a gate that always executes) or no longer applies gauntlet/gauntlet.gradle; or the log "
            f"level hides task lines (org.gradle.logging.level in gradle.properties, -q). The gate is red until Gradle "
            f"runs every one of them.")


def verify_ran(task, log):
    """CI: the same judgement of a finished, green Gradle run as the Stop hook's. 0 = every gate task ran."""
    try:
        with open(log, "rb") as f:
            output = f.read().decode("utf-8", "replace")
    except (OSError, TypeError) as e:
        print(f"gauntlet: cannot read the Gradle output {log}: {e}")
        return 2
    missing = not_run(task, output)
    if missing:
        print(not_run_message(task, missing))
        return 1
    return 0


def tree_fingerprint(exclude=()):
    """Content of every tracked or untracked (not ignored) file under the watched paths - not HEAD."""
    h = hashlib.sha256()
    pathspec = watched() + [f":(exclude){e}" for e in exclude]
    files = git_bytes("ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", *pathspec)
    for path in sorted(set(p for p in files.decode("utf-8", "replace").split("\0") if p)):
        h.update(path.encode() + b"\0")
        try:
            with open(path, "rb") as f:
                for chunk in iter(lambda: f.read(1 << 16), b""):
                    h.update(chunk)
        except OSError:
            h.update(b"<missing>")
    return h.hexdigest()


PROPERTY = re.compile(r"([^=:\s]+)\s*[=:]?\s*(.*)$")


def parse_properties(text):
    props = {}
    for line in text.splitlines():
        m = PROPERTY.match(line.strip())
        if m and line.strip()[0] not in "#!":
            props[m.group(1)] = m.group(2).strip()
    return props


def properties():
    """The settings of gauntlet.properties with the separators java.util.Properties reads (gauntlet.gradle loads
    the same file with it): `key=value`, `key: value` and `key value`; `#` and `!` start a comment line."""
    return parse_properties(read(PROPERTIES))


def module_properties(module):
    """The common settings with the module's overlay on top; module keys win, as in gauntlet.gradle."""
    props = properties()
    if module != ".":
        props.update(parse_properties(read(os.path.join("gauntlet", "config", module, "gauntlet.properties"))))
    return props


def test_inputs(props):
    """Files outside the source sets that the tests read (INPUT_KEYS), relative to the build: a change to one - or its
    removal - is a code change. An absolute path or one with '..' is no input here; gauntlet.gradle rejects it."""
    entries = [p.strip() for key in INPUT_KEYS for p in props.get(key, "").split(",") if p.strip()]
    return list(dict.fromkeys(p for p in entries if not p.startswith("/") and ".." not in p.split("/")))


def module_dirs(value):
    """The module list as CI reads it: comma-separated, every entry stripped of blanks and slashes and read as a
    path - ./core, core/. and core/ are core, . and ./ the build at the repository root. Git names a changed
    path without ./, so an entry kept as written named no path and its build was never run."""
    dirs = []
    for entry in value.split(","):
        d = os.path.normpath(entry.strip()).replace(os.sep, "/").strip("/") if entry.strip() else ""
        if d and d not in dirs:
            dirs.append(d)
    return [] if dirs == ["."] else dirs


def under(module, rel):
    """A path inside a module as git names it ('.' = the build at the repository root: no prefix)."""
    return rel if module == "." else module + "/" + rel


def configure_module():
    """Point every path at the modules' build directories when the build is not at the repository root."""
    global MODULES, WATCHED, REPORT_DIRS, INPUTS
    MODULES = module_dirs(properties().get("module", ""))
    if MODULES:
        WATCHED = [under(m, p) for m in MODULES for p in BUILD_FILES] + ["gauntlet"]
        REPORT_DIRS = [under(m, p) for m in MODULES for p in REPORT_DIRS]
        INPUTS = [under(m, p) for m in MODULES for p in test_inputs(module_properties(m))]
    else:
        INPUTS = test_inputs(properties())


def watched():
    """Pathspecs of what the gate checks: build paths that do not exist are left out, test inputs never are -
    deleting one must count as a change."""
    return existing(WATCHED) + INPUTS


def affected_modules(changed):
    """Which builds a change touches: a module's own directory, its gauntlet/config/<module>/ overlay, or -
    for any shared gauntlet file (common config/, tools/, gauntlet.gradle) - every module, since they all
    read it. Without modules the single build at the repository root is '.'."""
    if not MODULES:
        return ["."]
    touched, shared = set(), False
    for p in changed:
        # Longest (most specific) prefix wins: with module=core,core/adapter a change inside core/adapter
        # belongs to the nested build, not the parent core - the first match depended on module order.
        # A root build listed beside modules ('.') owns what no module owns, except the gauntlet's own files.
        owner = max((m for m in MODULES if p.startswith(m + "/") or (m == "." and not p.startswith("gauntlet/"))),
                    key=lambda m: 0 if m == "." else len(m), default=None)
        if owner:
            touched.add(owner)
        elif p.startswith("gauntlet/config/"):
            overlay = max((m for m in MODULES if p.startswith("gauntlet/config/" + m + "/")), key=len, default=None)
            if overlay:
                touched.add(overlay)
            else:
                shared = True   # a common config file: every module reads it
        elif p.startswith("gauntlet/"):
            shared = True       # shared tooling, gradle script or hooks: every module runs it
    return list(MODULES) if shared else [m for m in MODULES if m in touched]


def features_dirs():
    # as gauntlet.gradle reads it: an empty value is the default, "./src/.../features/" is "src/.../features"
    value = properties().get("acceptance.features", "").strip() or DEFAULT_FEATURES
    rel = os.path.normpath(value).replace(os.sep, "/").strip("/")
    return [under(m, rel) for m in MODULES] if MODULES else [rel]


def specs_pending_approval(features):
    """Feature files that are changed but not committed wait for the human's 'yes' in the chat.
    Once committed on the branch they are the approved specification (and the guard freezes them)."""
    dirs = [f for f in features if os.path.isdir(f)]
    if not dirs:
        return False
    status = git_bytes("status", "--porcelain", "--untracked-files=all", "--", *dirs)
    return bool(status.strip())


def mtime(path):
    try:
        return os.path.getmtime(path)
    except OSError:
        return 0.0


def newer_than(stamp, dirs):
    """Any file under dirs modified after stamp."""
    for d in dirs:
        for root, _, files in os.walk(d):
            for name in files:
                if mtime(os.path.join(root, name)) > stamp:
                    return True
    return False


def module_report_dirs(module):
    """The Gradle report directories of ONE module ('.' = the repository root), so its red-run cache is
    judged against its own reports rather than every module's combined REPORT_DIRS."""
    base = "" if module == "." else module + "/"
    return [base + "build/test-results", base + "build/reports"]


def state_dir():
    path = git("rev-parse", "--git-path", "gauntlet-hook")
    if not path:
        return None
    try:
        os.makedirs(path, exist_ok=True)
    except OSError:
        return None     # e.g. .git/gauntlet-hook is a file: no state, the caller says so
    return path


def git(*args):
    return git_bytes(*args).decode("utf-8", "replace").strip()


def git_bytes(*args):
    try:
        return subprocess.run(["git", "-c", "core.quotePath=false", *args], stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL).stdout
    except OSError:
        return b""


def existing(paths):
    """Pathspec for the read-only git queries. Keep a watched path that exists on disk OR is still known
    to git (index or HEAD): a tracked build.gradle the agent deleted is absent on disk but must stay in
    scope, otherwise its deletion is invisible and the module's build is skipped. A deleted directory
    stays while git tracks files under it. Fall back to '.' only when nothing is known."""
    kept = [p for p in paths if os.path.exists(p)]
    absent = [p for p in paths if not os.path.exists(p)]
    if absent:
        known = set(git("ls-files", "--", *absent).splitlines())
        known.update(git("ls-tree", "-r", "--name-only", "HEAD", "--", *absent).splitlines())
        kept += [p for p in absent if p in known or any(k.startswith(p + "/") for k in known)]
    return kept or ["."]


def safe(value):
    return re.sub(r"[^A-Za-z0-9_.-]", "_", str(value))[:80]


def read(path):
    # a byte that is not UTF-8 (a comment saved as ISO-8859-1 or CP1251) is replaced, as Gradle replaces it: a hook
    # that died on it exited 1, and Claude Code let the agent stop without the gate ever running
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().strip()
    except OSError:
        return ""


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def remove(path):
    try:
        os.remove(path)
    except OSError:
        pass


if __name__ == "__main__":
    sys.exit(main())
