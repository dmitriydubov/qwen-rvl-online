#!/usr/bin/env python3
"""Antigravity CLI (agy) hooks -> the gauntlet's own hooks, unchanged.

Part of the Gemini (Antigravity) adapter. Proposed place: gauntlet/adapters/antigravity/agy_hooks.py, added by the
human like any kit change, so the CI check `gauntlet kit unchanged` covers it with the rest of gauntlet/.

The script only translates. Every decision is made by gauntlet/hooks/guard.py, stop_gate.py and session_start.sh:
  - agy sends one JSON event on stdin: hookEventName, conversationId, cwd, workspacePaths, toolCall {name, args, id},
    and for Stop continuation_active; run_command arguments are CommandLine and Cwd, file tools carry TargetFile.
    Antigravity's wire format carries paths as file:/// URIs (its Python SDK turns them into plain paths before its
    own hooks see them), so every path argument is read through plain_path() before guard.py judges it;
  - agy reads one JSON object from stdout and needs exit code 0: per the docs a hook that exits non-zero, times out
    or prints no valid JSON is logged and ignored (fail-open), while reports on the vendor's tracker (#1183) saw a
    failing PreToolUse hook deny the call. Either way every path below exits 0 and prints JSON;
  - PreToolUse answers {"decision": "allow" | "deny", "reason": ...}; "allow" hands the call to the next hook and the
    normal permission policy, it does not approve anything by itself;
  - Stop answers {"decision": "continue", "reason": ...} to keep the agent working, {} to let it stop.
Sources: antigravity.google/docs/hooks (read 2026-11-24 through a search engine: the site is not reachable from the
session that wrote this), google-antigravity/antigravity-cli CHANGELOG.md 1.4.7, 1.5.4, 1.5.6 (read 2026-11-24).
Not run against a live agy: check it with the smoke test of the adapter (section A.6 of gemini.md).

  agy_hooks.py session-start
  agy_hooks.py pre-tool --role shared|main|specifier|hardener|reviewer
  agy_hooks.py stop --task gauntletFast [--max-attempts 3] [--timeout 1700] [--agent hardener]
               --agent <role>: the Stop hook of a role's own hooks.json; judged like the kit's SubagentStop, with
               its own attempt counter, so it never shares one with the workspace Stop hook of the same conversation
  agy_hooks.py selftest <project>          what this script patches in guard.py is still there, decisions as expected
"""
import contextlib
import datetime
import importlib.util
import io
import json
import os
import re
import subprocess
import sys
import urllib.parse
import urllib.request

sys.dont_write_bytecode = True

HERE = os.path.dirname(os.path.realpath(__file__))
REPO_ROOT = os.path.realpath(os.path.join(HERE, "..", "..", ".."))
KIT_HOOKS = os.path.realpath(os.path.join(HERE, "..", "..", "hooks"))
# The harness's own configuration is human-owned like .claude/ and CLAUDE.md: rules, agents, hooks, settings.
EXTRA_OWNED = (".agents/", ".gemini/", "GEMINI.md", "AGENTS.md")
# agy settings, permissions, global hooks and rules (~/.gemini); user-global skills (~/.agents/skills, agy 1.5.16)
USER_CONFIG = ("~/.gemini", "~/.agents")
WRITE_TOOL = re.compile(r"write|replace|edit|create|delete|remove|move|rename", re.I)
PATH_ARG = re.compile(r"(file|path)$", re.I)     # TargetFile, AbsolutePath, file_path, path, directory_path ...
STATUS_FILE = "agy-session-status.txt"
RELEASE_FILE = "agy-last-stop.txt"


def main(argv):
    if not argv:
        return answer({})
    command, rest = argv[0], argv[1:]
    if command == "selftest":
        return selftest(rest[0] if rest else REPO_ROOT)
    event = read_event()
    project = project_of(event)
    try:
        if command == "pre-tool":
            return answer(pre_tool(event, project, option(rest, "--role", "shared")))
        if command == "stop":
            return answer(stop(event, project, rest))
        if command == "session-start":
            return answer(session_start(event, project))
    except Exception as error:       # fail-open like guard.py, but say so in agy's hook log
        sys.stderr.write(f"[gauntlet agy] {command} failed: {error!r}\n")
        return answer({"decision": "allow"} if command == "pre-tool" else {})
    return answer({})


def answer(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False))
    return 0


def read_event():
    try:
        event = json.load(sys.stdin)
        return event if isinstance(event, dict) else {}
    except Exception:
        return {}


def option(args, name, default):
    return args[args.index(name) + 1] if name in args and args.index(name) + 1 < len(args) else default


def has_gauntlet_kit(path):
    return bool(path) and os.path.isfile(os.path.join(path, "gauntlet", "config", "gauntlet.properties"))


def project_of(event):
    """Find the guarded repository root even in multi-workspace remote VMs where workspacePaths[0] and cwd are $HOME."""
    raw_paths = [plain_path(p) for p in (event.get("workspacePaths") or []) if isinstance(p, str) and p]
    candidates = list(raw_paths)
    for extra in (event.get("cwd"), os.getcwd(), REPO_ROOT):
        if isinstance(extra, str) and extra:
            candidates.append(plain_path(extra))
    for cand in candidates:
        real = os.path.realpath(cand)
        if has_gauntlet_kit(real):
            return real
    if has_gauntlet_kit(REPO_ROOT):
        return REPO_ROOT
    return (raw_paths[0] if raw_paths else None) or plain_path(event.get("cwd") or os.getcwd())


def tool_args(event):
    call = event.get("toolCall") or {}
    args = call.get("args") or {}
    if isinstance(args, str):
        try:
            args = json.loads(args)
        except ValueError:
            args = {}
    return (call.get("name") or ""), (args if isinstance(args, dict) else {})


def plain_path(value):
    """A path argument as a filesystem path. A file:/// URI left as it is reads to guard.py as a relative path
    ("file:/home/...") that lies under no protected prefix, so it would pass every check."""
    text = str(value)
    parsed = urllib.parse.urlparse(text)
    if parsed.scheme.lower() == "file":
        return urllib.request.url2pathname(parsed.path) or text
    return text


# ----------------------------------------------------------------------------- PreToolUse -> guard.py

def load_guard(role):
    spec = importlib.util.spec_from_file_location("gauntlet_guard", os.path.join(KIT_HOOKS, "guard.py"))
    guard = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(guard)
    guard.HUMAN_OWNED = tuple(guard.HUMAN_OWNED) + tuple(p for p in EXTRA_OWNED if p not in guard.HUMAN_OWNED)
    kit_user_config = guard.user_config_violation
    homes = [(name, os.path.realpath(os.path.expanduser(name))) for name in USER_CONFIG]

    def user_config_violation(path):
        reason = kit_user_config(path)
        if reason or not path:
            return reason
        expanded = os.path.expanduser(path)
        if os.path.isabs(expanded):
            absolute = os.path.realpath(expanded)
            for name, home in homes:
                if absolute == home or absolute.startswith(home + os.sep):
                    return (f"{name} is the user's Antigravity configuration (settings, permissions, hooks, rules, "
                            f"skills); it could switch the gauntlet off. Do not touch it.")
        return None

    guard.user_config_violation = user_config_violation
    if role == "shared":
        # agy does not say in a hook which agent made the call (google-antigravity/antigravity-cli#922, #1527), and
        # the workspace hooks run for subagents too. Until the spec is frozen, the specifier must be able to write it:
        # here the feature files are left to the roles' own hooks; once frozen they are closed for everyone.
        kit_init = guard.Context.__init__

        def init(self, project):
            kit_init(self, project)
            if not self.frozen:
                self.features_dirs = ()

        guard.Context.__init__ = init
    return guard


def claude_event(event, project, role):
    name, args = tool_args(event)
    if name == "run_command":
        tool, params = "Bash", {"command": str(args.get("CommandLine") or args.get("command") or "")}
        cwd = plain_path(args.get("Cwd") or args.get("cwd") or event.get("cwd") or project)
    else:
        targets = [args["TargetFile"]] if args.get("TargetFile") else []
        if not targets and WRITE_TOOL.search(name):
            targets = [v for k, v in args.items() if isinstance(v, str) and v and PATH_ARG.search(k)]
        if not targets:
            return None                  # reads, searches, subagents, MCP: nothing for the guard to judge
        targets = [plain_path(t) for t in targets]
        tool, params, cwd = "Write", {"file_path": targets[0]}, plain_path(event.get("cwd") or project)
        if len(targets) > 1:
            params["_more"] = targets[1:]
    out = {"hook_event_name": "PreToolUse", "tool_name": tool, "tool_input": params, "cwd": cwd,
           "session_id": event.get("conversationId") or "agy"}
    if role in ("specifier", "hardener", "reviewer"):
        out["agent_type"] = role
    return out


def ask_guard(guard, event, project):
    stdin, stdout = sys.stdin, io.StringIO()
    previous = os.environ.get("CLAUDE_PROJECT_DIR")
    os.environ["CLAUDE_PROJECT_DIR"] = project
    try:
        sys.stdin = io.StringIO(json.dumps(event))
        with contextlib.redirect_stdout(stdout):
            guard.main()
    finally:
        sys.stdin = stdin
        if previous is None:
            os.environ.pop("CLAUDE_PROJECT_DIR", None)
        else:
            os.environ["CLAUDE_PROJECT_DIR"] = previous
    text = stdout.getvalue().strip()
    if not text:
        return None
    out = json.loads(text).get("hookSpecificOutput") or {}
    return out.get("permissionDecisionReason") if out.get("permissionDecision") == "deny" else None


def pre_tool(event, project, role):
    translated = claude_event(event, project, role)
    if translated is None:
        return {"decision": "allow"}
    guard = load_guard(role)
    paths = [translated["tool_input"].get("file_path")] + translated["tool_input"].pop("_more", [])
    for path in paths if translated["tool_name"] == "Write" else [None]:
        if path is not None:
            translated["tool_input"]["file_path"] = path
        reason = ask_guard(guard, translated, project)
        if reason:
            return {"decision": "deny", "reason": reason}
    return {"decision": "allow"}


# ----------------------------------------------------------------------------- Stop -> stop_gate.py

def stop(event, project, args):
    agent = option(args, "--agent", "")
    if "--agent" in args:
        at = args.index("--agent")
        args = args[:at] + args[at + 2:]
    kit_event = {"hook_event_name": "Stop", "session_id": event.get("conversationId") or "agy", "cwd": project,
                 "stop_hook_active": bool(event.get("continuation_active") or event.get("stopHookActive"))}
    if agent:
        # A role's own Stop hook (the hardener's mutationGate) is the kit's SubagentStop. agy fires the workspace
        # Stop hook for the subagent's conversation as well (#1527): without its own agent_id the two hooks would
        # share stop_gate.py's attempt counter and last-stop mark, and a green gauntletFast would let the
        # mutationGate run be skipped (and the other way round).
        kit_event.update(hook_event_name="SubagentStop", agent_id=agent, agent_type=agent)
    env = dict(os.environ, CLAUDE_PROJECT_DIR=project)
    env.setdefault("ORG_GRADLE_PROJECT_publicRepositories", "true")
    proc = subprocess.run([sys.executable, "-I", "-B", os.path.join(KIT_HOOKS, "stop_gate.py")] + args,
                          input=json.dumps(kit_event), capture_output=True, text=True, env=env, cwd=project)
    if proc.returncode == 2:
        return {"decision": "continue", "reason": proc.stderr.strip() or "[gauntlet] the gate is RED: fix it."}
    note = system_message(proc.stdout)
    if note:
        # agy shows nothing to the agent when it lets it stop: the note goes to the hook log and to a file that
        # the agent reads before its report (GEMINI.md, "Перед отчётом").
        sys.stderr.write(note + "\n")
        keep(project, RELEASE_FILE, note)
    return {}


def system_message(stdout):
    for line in (stdout or "").splitlines():
        try:
            message = json.loads(line).get("systemMessage")
        except (ValueError, AttributeError):
            continue
        if message:
            return message
    return ""


def keep(project, name, text):
    state = subprocess.run(["git", "rev-parse", "--git-path", "gauntlet-hook"], cwd=project,
                           capture_output=True, text=True).stdout.strip()
    if not state:
        return
    path = state if os.path.isabs(state) else os.path.join(project, state)
    os.makedirs(path, exist_ok=True)
    with open(os.path.join(path, name), "w", encoding="utf-8") as f:
        f.write(text.rstrip("\n") + "\n")


# ----------------------------------------------------------------------------- SessionStart -> session_start.sh

def session_start(event, project):
    kit_event = {"hook_event_name": "SessionStart", "session_id": event.get("conversationId") or "agy",
                 "cwd": project, "source": "startup"}
    env = dict(os.environ, CLAUDE_PROJECT_DIR=project)
    env.setdefault("ORG_GRADLE_PROJECT_publicRepositories", "true")
    proc = subprocess.run(["bash", os.path.join(KIT_HOOKS, "session_start.sh")], input=json.dumps(kit_event),
                          capture_output=True, text=True, env=env, cwd=project, timeout=170)
    status = (proc.stdout or "").strip() or "[gauntlet] SessionStart printed nothing"
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    keep(project, STATUS_FILE, f"{status}\nwritten: {stamp}; conversation: {kit_event['session_id']}")
    return {"injectSteps": [{"systemMessage": {"systemMessage": status}}]}


# ----------------------------------------------------------------------------- selftest

def selftest(project):
    """Run in a checkout with the kit installed: what this wrapper relies on in guard.py, and a few decisions."""
    project = os.path.realpath(project)
    failures = []
    guard = load_guard("shared")
    for name in ("HUMAN_OWNED", "user_config_violation", "Context", "main"):
        if not hasattr(guard, name):
            failures.append(f"guard.py has no {name}: the wrapper must be updated with the kit")
    ctx = guard.Context(project)
    if not hasattr(ctx, "features_dirs") or not hasattr(type(ctx), "frozen"):
        failures.append("guard.Context has no features_dirs/frozen: the shared role cannot be built")
    multi_ws_project = project_of({"workspacePaths": ["/tmp", project], "cwd": "/tmp"})
    if multi_ws_project != project:
        failures.append(f"project_of multi-workspace resolved {multi_ws_project!r}, expected {project!r}")

    def decide(role, name, args):
        event = {"hookEventName": "PreToolUse", "conversationId": "selftest", "cwd": "/tmp",
                 "workspacePaths": ["/tmp", project], "toolCall": {"name": name, "args": args, "id": "t"}}
        return pre_tool(event, project_of(event), role)["decision"]

    home = os.path.expanduser(USER_CONFIG[0])
    pfx = "" if not ctx.modules or ctx.modules[0] == "." else ctx.modules[0] + "/"
    cases = [
        ("shared", "write_to_file", {"TargetFile": os.path.join(project, "gauntlet/config/gauntlet.properties")}, "deny"),
        ("shared", "replace_file_content", {"TargetFile": os.path.join(project, "GEMINI.md")}, "deny"),
        ("shared", "write_to_file", {"TargetFile": os.path.join(project, ".agents/hooks.json")}, "deny"),
        ("shared", "write_to_file", {"TargetFile": os.path.join(home, "antigravity-cli/settings.json")}, "deny"),
        ("shared", "run_command", {"CommandLine": "sed -i s/8/80/ gauntlet/config/gauntlet.properties", "Cwd": project}, "deny"),
        ("shared", "run_command", {"CommandLine": "echo '{}' > .agents/hooks.json", "Cwd": project}, "deny"),
        ("shared", "run_command", {"CommandLine": "git push --force origin HEAD", "Cwd": project}, "deny"),
        ("shared", "run_command", {"CommandLine": "gh pr merge 7", "Cwd": project}, "deny"),
        ("shared", "run_command", {"CommandLine": "./gradlew gauntletFast --continue", "Cwd": project}, "allow"),
        ("shared", "write_to_file", {"TargetFile": os.path.join(project, f"{pfx}src/main/java/A.java")}, "allow"),
        ("shared", "view_file", {"AbsolutePath": os.path.join(project, "gauntlet/CLAUDE.md")}, "allow"),
        ("hardener", "write_to_file", {"TargetFile": os.path.join(project, f"{pfx}src/main/java/A.java")}, "deny"),
        ("hardener", "write_to_file", {"TargetFile": os.path.join(project, f"{pfx}src/test/java/ATest.java")}, "allow"),
        ("reviewer", "write_to_file", {"TargetFile": os.path.join(project, f"{pfx}src/test/java/ATest.java")}, "deny"),
        ("reviewer", "run_command", {"CommandLine": "git commit -am x", "Cwd": project}, "deny"),
        ("reviewer", "run_command", {"CommandLine": "git diff origin/main", "Cwd": project}, "allow"),
        ("specifier", "write_to_file", {"TargetFile": os.path.join(project, f"{pfx}src/main/java/A.java")}, "deny"),
        # the wire format of a path (file:/// URI), other write tools and their lower-case path keys, ~/.agents
        ("shared", "write_to_file", {"TargetFile": "file://" + os.path.join(project, "gauntlet/config/x")}, "deny"),
        ("shared", "edit_file", {"file_path": os.path.join(project, "GEMINI.md")}, "deny"),
        ("shared", "run_command", {"CommandLine": "echo x > gauntlet/config/x", "Cwd": "file://" + project}, "deny"),
        ("shared", "create_file", {"path": os.path.join(os.path.expanduser(USER_CONFIG[1]), "skills/x/SKILL.md")},
         "deny"),
    ]
    features = guard.Context(project)
    feature = os.path.join(project, (load_guard("main").Context(project).features_dirs or ("features/",))[0], "x.feature")
    frozen = features.frozen if hasattr(type(features), "frozen") else None
    cases += [("specifier", "write_to_file", {"TargetFile": feature}, "deny" if frozen else "allow"),
              ("shared", "write_to_file", {"TargetFile": feature}, "deny" if frozen else "allow"),
              ("main", "write_to_file", {"TargetFile": feature}, "deny")]
    for role, name, args, expected in cases:
        got = decide(role, name, args)
        mark = "ok  " if got == expected else "FAIL"
        if got != expected:
            failures.append(f"{role} {name} {args}: {got}, expected {expected}")
        print(f"{mark} {role:9} {name:22} {json.dumps(args, ensure_ascii=False)[:90]} -> {got}")
    print(f"spec frozen: {frozen}")
    print("selftest: " + ("PASSED" if not failures else f"{len(failures)} FAILED\n" + "\n".join(failures)))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
