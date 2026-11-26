#!/usr/bin/env python3
"""Grok Build -> gauntlet hooks bridge (gauntlet/adapters/grok/grok_hooks.py).

Grok Build (xAI's `grok` CLI) loads Claude-format hook files and also sends Claude's snake_case spelling of the
common keys (session_id, tool_name, tool_input, stop_hook_active ...). What the kit's hooks read still differs:
  - tool names are Grok's own: run_terminal_command, search_replace, write, hashline_edit, apply_patch. guard.py
    judges Bash, Edit, Write, MultiEdit and NotebookEdit and lets every other name through, so on its own it allows
    every edit and every command a Grok agent makes;
  - a subagent is named by subagentType and identified by subagentId; agent_type and agent_id are absent, so the
    role scopes in guard.py never apply (a subagent's events carry its own session id, so stop marks stay apart);
  - a hook input over 128 KiB arrives cut (toolInputTruncated): guard.py must not judge a command or a patch it
    cannot read whole;
  - SessionStart output never reaches the model, so the hook status line has to travel another way.
This bridge rewrites each event into the shape the kit's hooks read and runs those hooks unchanged.

  grok_hooks.py session-start        SessionStart: session_start.sh; its status line is written to
                                     $(git rev-parse --git-path gauntlet-hook)/grok-status.txt, which the agent
                                     prints first (per working copy, like stop_gate.py's own state)
  grok_hooks.py guard                PreToolUse: guard.py on the rewritten event; AGENTS.md, .grok/ and Grok's user
                                     configuration count as human-owned too; a cut shell command or patch, or an
                                     input that is not an object, is refused
  grok_hooks.py stop ARGS...         Stop / SubagentStop: stop_gate.py ARGS on the rewritten event. On Stop it first
                                     keeps the agent working while its own subagents or a Gradle run still go on
  grok_hooks.py report               SubagentStop of the reviewer or a read-only card: the final message has the
                                     sections of the report format; at most two returns, then it lets the agent go

Like the kit's hooks it fails open on input it cannot read: a broken hook must not brick a session. CI stays the
judge. Humans switch it off with the kit's own variables (GAUNTLET_GUARD=off, GAUNTLET_STOP_HOOK=off).
"""
import importlib.util
import io
import json
import os
import re
import subprocess
import sys
import time

sys.dont_write_bytecode = True          # importing guard.py must not leave __pycache__ inside gauntlet/

HERE = os.path.dirname(os.path.realpath(__file__))
REPO_ROOT = os.path.realpath(os.path.join(HERE, "..", "..", ".."))
ROOT = os.environ.get("CLAUDE_PROJECT_DIR") or os.environ.get("GROK_WORKSPACE_ROOT") or (
    REPO_ROOT if os.path.isfile(os.path.join(REPO_ROOT, "gauntlet", "config", "gauntlet.properties")) else os.getcwd()
)
HOOKS = os.path.join(ROOT, "gauntlet", "hooks")
# Grok tool -> the Claude tool guard.py judges. The input fields guard.py reads (file_path, command) are the same.
TOOLS = {"run_terminal_command": "Bash", "run_terminal_cmd": "Bash", "search_replace": "Edit",
         "hashline_edit": "Edit", "write": "Write"}
PATCH_PATH = re.compile(r"^\*\*\* (?:Add File|Update File|Delete File|Move to): (.+?)\s*$", re.M)
ROLES = {"specifier", "hardener", "reviewer"}
ROLE_HINT = re.compile(
    r"(?:\[ROLE:\s*(specifier|hardener|reviewer|gauntlet-card-[a-z0-9-]+)\]"
    r"|^\s*(specifier|hardener|reviewer|gauntlet-card-[a-z0-9-]+)\s*[:—-])",
    re.I,
)
# Subagent types that may not change files or git state: guard.py gives them the reviewer's rights.
READ_ONLY = re.compile(r"^(?:gauntlet-card-[a-z0-9-]+|explore|plan)$")
# Paths a Grok agent must not change on top of guard.py's HUMAN_OWNED: its project instructions and configuration.
GROK_OWNED = ("AGENTS.md", ".grok/")
# Grok's user configuration (GROK_HOME, default ~/.grok): what could switch hooks, trust or rules off.
GROK_USER = ("config.toml", "managed_config.toml", "requirements.toml", "sandbox.toml", "trusted_folders.toml",
             "hooks", "hooks-paths", "disabled-hooks", "agents", "rules", "personas", "roles", "plugins", "skills")
REPORT_SECTIONS = ("## Итог", "## Проверено", "## Не проверено", "## Вопросы и риски")
COVERAGE = re.compile(r"покрытие:[^\n]*?\d+\s*из\s*\d+", re.I)
EVIDENCE = re.compile(r"доказательство:", re.I)
CLIPPED = re.compile(r"\[\+\d+ chars\]\s*$")
MAX_RETURNS = 2
# Grok cuts a tool input whose JSON passes 128 KiB (toolInputTruncated): 1.0.45 sends the whole input as one string,
# 1.0.92 keeps the keys and cuts long strings. Its hooks guide: a guard that checks a string's text should deny then.
CUT = ("[gauntlet guard] Grok cut this call's input for the hooks (over 128 KiB), so the guard cannot read all of it. "
       "Split the command into shorter ones, or write long text to a file with the file tools first.")


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    raw = sys.stdin.read()
    try:
        event = json.loads(raw)
    except ValueError:
        return 0
    if not isinstance(event, dict):
        return 0
    if mode == "session-start":
        return session_start(event, raw)
    if mode == "guard":
        return guard(event)
    if mode == "stop":
        return stop(event, sys.argv[2:])
    if mode == "report":
        return report(event)
    if mode == "subagent-stop":
        return subagent_stop(event)
    sys.stderr.write(f"grok_hooks.py: unknown mode {mode!r}\n")
    return 0


# ----------------------------------------------------------------------------- shared

def infer_kind(event):
    kind = event.get("subagentType") or event.get("agent_type")
    if kind and kind != "general-purpose":
        return kind
    for field in ("description", "subagentDescription", "prompt", "subagentPrompt"):
        val = event.get(field)
        if isinstance(val, str) and val:
            m = ROLE_HINT.search(val)
            if m:
                return (m.group(1) or m.group(2)).lower()
    return kind


def role(event):
    """The agent_type guard.py and stop_gate.py read: the kit's role, reviewer rights for a read-only card, or None."""
    kind = infer_kind(event)
    if not kind or kind == "general-purpose":
        return None
    if kind in ROLES:
        return kind
    return "reviewer" if READ_ONLY.match(kind) else kind


def subagent_stop(event):
    kind = infer_kind(event)
    if kind == "hardener":
        patched = dict(event, subagentType="hardener")
        return stop(patched, ["--task", "mutationGate", "--max-attempts", "2", "--timeout", "3300"])
    if kind == "reviewer" or (kind and READ_ONLY.match(kind) and kind.startswith("gauntlet-card-")):
        patched = dict(event, subagentType=kind)
        return report(patched)
    return 0


def claude_event(event, **changes):
    out = dict(event)
    out.setdefault("session_id", event.get("sessionId"))
    out.setdefault("stop_hook_active", bool(event.get("stopHookActive")))
    out.setdefault("last_assistant_message", event.get("lastAssistantMessage"))
    if event.get("subagentId"):
        out["agent_id"] = event["subagentId"]
    agent = role(event)
    if agent:
        out["agent_type"] = agent
    else:
        out.pop("agent_type", None)
    out.update(changes)
    return out


def state_dir():
    """The folder stop_gate.py keeps its state in: per working copy, so parallel Grok worktrees do not share it."""
    try:
        path = subprocess.run(["git", "rev-parse", "--git-path", "gauntlet-hook"], cwd=ROOT, capture_output=True,
                              text=True, timeout=10).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None
    if not path:
        return None
    path = path if os.path.isabs(path) else os.path.join(ROOT, path)
    try:
        os.makedirs(path, exist_ok=True)
    except OSError:
        return None
    return path


def safe(text):
    return re.sub(r"[^A-Za-z0-9_.-]", "_", str(text))[:120]


# ----------------------------------------------------------------------------- SessionStart

def session_start(event, raw):
    env = dict(os.environ, CLAUDE_PROJECT_DIR=ROOT)
    env.setdefault("ORG_GRADLE_PROJECT_publicRepositories", "true")
    try:
        proc = subprocess.run(["bash", os.path.join(HOOKS, "session_start.sh")], input=raw.encode(), cwd=ROOT,
                              env=env, capture_output=True, timeout=170)
        lines = proc.stdout.decode("utf-8", "replace").strip().splitlines()
        status = lines[-1] if lines else "[gauntlet] WARNING: session_start.sh printed nothing"
    except (OSError, subprocess.SubprocessError) as e:
        status = f"[gauntlet] WARNING: session_start.sh did not run: {e}"
    folder = state_dir()
    if folder:
        stamp = time.strftime("%Y-%m-%dT%H:%M:%S%z")
        try:
            with open(os.path.join(folder, "grok-status.txt"), "w", encoding="utf-8") as f:
                f.write(f"{stamp} session {event.get('sessionId') or event.get('session_id')}: {status}\n")
        except OSError:
            pass                                     # a read-only sandbox: no status line, the agent says so
    return 0


# ----------------------------------------------------------------------------- PreToolUse

def guard(event):
    tool = event.get("toolName") or event.get("tool_name") or ""
    params = event.get("toolInput") or event.get("tool_input") or {}
    if tool not in TOOLS and tool != "apply_patch":
        return 0
    if event.get("toolInputTruncated") and os.environ.get("GAUNTLET_GUARD", "").lower() != "off" and (
            not isinstance(params, dict) or tool == "apply_patch" or TOOLS[tool] == "Bash"):
        sys.stdout.write(json.dumps({"hookSpecificOutput": {      # a file tool's path is never cut: judged below
            "hookEventName": "PreToolUse", "permissionDecision": "deny", "permissionDecisionReason": CUT}}))
        return 0
    if not isinstance(params, dict):
        return 0
    if tool in TOOLS:
        calls = [(TOOLS[tool], params)]
    else:                                                # apply_patch: every file the patch names
        text = params.get("patch") or params.get("input") or ""
        calls = [("Edit", {"file_path": p}) for p in PATCH_PATH.findall(text if isinstance(text, str) else "")]
    for claude_tool, tool_input in calls:
        verdict = judge(claude_event(event, tool_name=claude_tool, tool_input=tool_input, hook_event_name="PreToolUse"))
        if verdict:
            sys.stdout.write(verdict)
            return 0
    return 0


def load_guard():
    spec = importlib.util.spec_from_file_location("gauntlet_guard", os.path.join(HOOKS, "guard.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.HUMAN_OWNED = tuple(module.HUMAN_OWNED) + GROK_OWNED
    kit_check = module.user_config_violation

    def user_config_violation(path):
        return kit_check(path) or grok_user_violation(path)

    module.user_config_violation = user_config_violation
    return module


def grok_user_violation(path):
    if not path:
        return None
    home = os.path.realpath(os.path.expanduser(os.environ.get("GROK_HOME") or "~/.grok"))
    target = os.path.expanduser(path)
    if not os.path.isabs(target):
        return None
    target = os.path.realpath(target)
    for name in GROK_USER:
        owned = os.path.join(home, name)
        if target == owned or target.startswith(owned + os.sep):
            return (f"{owned} is Grok Build's user configuration (it could switch hooks, trust or rules off). "
                    f"Do not touch it.")
    return None


def judge(claude):
    """guard.py's verdict on one rewritten event: its JSON output, or '' when it allows the call."""
    try:
        module = load_guard()
    except Exception:                                    # the kit changed or is missing: run guard.py as it is
        proc = subprocess.run([sys.executable, "-B", os.path.join(HOOKS, "guard.py")], input=json.dumps(claude),
                              capture_output=True, text=True, cwd=ROOT)
        if proc.returncode != 0 and not proc.stdout.strip():   # guard.py did not run: a hook failure (failClosed)
            raise SystemExit(f"grok_hooks.py: guard.py failed (exit {proc.returncode}): {proc.stderr.strip()[-300:]}")
        return proc.stdout.strip()
    stdin, stdout = sys.stdin, sys.stdout
    sys.stdin, sys.stdout = io.StringIO(json.dumps(claude)), io.StringIO()
    try:
        module.main()
        out = sys.stdout.getvalue()
    except SystemExit:
        out = sys.stdout.getvalue()
    finally:
        sys.stdin, sys.stdout = stdin, stdout
    return out.strip()


# ----------------------------------------------------------------------------- Stop / SubagentStop

def stop(event, args):
    name = event.get("hook_event_name")
    if name == "Stop":
        if event.get("reason") not in (None, "end_turn"):
            return 0                                     # the observe-only Stop at session end: nothing to gate
        waiting = still_running(event)
        if waiting:
            sys.stdout.write(json.dumps({"decision": "block", "reason": (
                "[gauntlet] Не заканчивай ход: ещё работают " + "; ".join(waiting) + ". Дождись каждого "
                "(get_command_or_subagent_output с timeout_ms > 0), прочитай отчёт сабагента целиком "
                "(если он обрезан - запроси полный вывод) и прими его по правилам приёмки; затем заканчивай.")},
                ensure_ascii=False))
            return 0
    claude = claude_event(event)
    env = dict(os.environ, CLAUDE_PROJECT_DIR=ROOT)
    env.setdefault("ORG_GRADLE_PROJECT_publicRepositories", "true")
    proc = subprocess.run([sys.executable, "-I", "-B", os.path.join(HOOKS, "stop_gate.py")] + list(args),
                          input=json.dumps(claude), capture_output=True, text=True, env=env, cwd=ROOT)
    sys.stdout.write(proc.stdout)
    sys.stderr.write(proc.stderr)
    return proc.returncode


def still_running(event):
    found = []
    for task in event.get("backgroundTasks") or []:
        if not isinstance(task, dict) or task.get("status") != "running":
            continue
        if task.get("type") == "subagent":
            found.append(f"сабагент {task.get('agentType') or ''} {task.get('id')} «{task.get('description') or ''}»")
        elif task.get("type") == "shell" and re.search(r"\bgradlew?\b", task.get("command") or ""):
            found.append(f"фоновый прогон Gradle {task.get('id')} «{task.get('command')}»")
    return found


# ----------------------------------------------------------------------------- report form

def report(event):
    if event.get("hook_event_name") != "SubagentStop":
        return 0
    text = event.get("lastAssistantMessage") or event.get("last_assistant_message") or ""
    missing = reviewer_gaps(text) if event.get("subagentType") == "reviewer" else card_gaps(text)
    if not missing:
        return 0
    folder = state_dir()
    counter = os.path.join(folder, "grok-report-" + safe(event.get("subagentId") or "unknown")) if folder else None
    returns = 0
    if counter and os.path.exists(counter):
        try:
            returns = int(open(counter, encoding="utf-8").read().strip() or 0)
        except (OSError, ValueError):
            returns = 0
    if returns >= MAX_RETURNS:
        sys.stdout.write(json.dumps({"systemMessage": (
            f"[gauntlet] Отчёт сабагента {event.get('subagentType')} всё ещё не по форме после {MAX_RETURNS} возвратов "
            f"(нет: {', '.join(missing)}). Координатор не принимает его: пометка «не проверено» и вопрос владельцу.")},
            ensure_ascii=False))
        return 0
    if counter:
        try:
            with open(counter, "w", encoding="utf-8") as f:
                f.write(str(returns + 1))
        except OSError:
            pass
    sys.stdout.write(json.dumps({"decision": "block", "reason": (
        f"[gauntlet] Отчёт не по форме (возврат {returns + 1} из {MAX_RETURNS}): нет " + "; ".join(missing) + ". "
        "Это доработка отчёта, не новый раунд работы: каждое утверждение - с доказательством (команда и вывод, "
        "файл:строка с цитатой или источник нужной версии), непроверенное - в «Не проверено». Верни отчёт целиком.")},
        ensure_ascii=False))
    return 0


def card_gaps(text):
    clipped = bool(CLIPPED.search(text))          # Grok clips the field at 32 768 characters: the tail may be cut
    required = REPORT_SECTIONS[:2] if clipped else REPORT_SECTIONS
    missing = [f"раздела «{s}»" for s in required if s not in text]
    if "## Проверено" in text:
        if not COVERAGE.search(text):
            missing.append("числа покрытия «покрытие: N из M»")
        if not EVIDENCE.search(text):
            missing.append("ни одного утверждения с «доказательство: …»")
    return missing


def reviewer_gaps(text):
    missing = []
    if not re.search(r"^VERDICT: (APPROVED|CHANGES REQUESTED)\s*$", text, re.M):
        missing.append("строки «VERDICT: APPROVED | CHANGES REQUESTED»")
    if not COVERAGE.search(text):
        missing.append("строки «Покрытие: файлы диффа N из M»")
    if "## Не проверено" not in text and not CLIPPED.search(text):
        missing.append("раздела «## Не проверено»")
    return missing


if __name__ == "__main__":
    sys.exit(main())
