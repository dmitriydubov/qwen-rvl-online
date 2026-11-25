#!/usr/bin/env python3
"""Self-test for the gauntlet hooks. Builds a throwaway git repository with a fake base branch and
runs guard.py / stop_gate.py against it the way Claude Code does (JSON on stdin, environment set).

    python3 gauntlet/hooks/selftest.py          # from any directory; needs git and python3

Gradle is stubbed with a fake ./gradlew that exits with the code found in a marker file, so the
Stop-gate logic is exercised without a JVM.
"""
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

HOOKS = os.path.dirname(os.path.abspath(__file__))
KIT = os.path.dirname(HOOKS)
FEATURES_REL = "src/acceptanceTest/resources/features"
MODULES = []          # Gradle builds inside the repository: [] = one build at the root, ["core", "adapter"] = monorepo
M = ""                # prefix of the module the shared tests work in ("" or "core/")
FEATURES = FEATURES_REL


def gate_tasks():
    """What the Stop hook demands a "> Task" line for after a green exit (stop_gate.GATE_TASKS), by task."""
    import importlib.util
    saved, sys.dont_write_bytecode = sys.dont_write_bytecode, True      # the kit stays as it is
    try:
        spec = importlib.util.spec_from_file_location("sg_tasks", os.path.join(HOOKS, "stop_gate.py"))
        sg = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(sg)
    finally:
        sys.dont_write_bytecode = saved
    return {task: sg.gate_tasks(task) for task in sg.GATE_TASKS}


# Green, it names every task it ran the way Gradle does with --console=plain: "> Task :<name>"
FAKE_GRADLEW = ("#!/bin/sh\ncode=$(cat build/gauntlet/fake-exit 2>/dev/null || echo 0)\n"
                "echo \"fake gradle $*\"\n"
                "if [ \"$code\" = 0 ]; then\n  case $1 in\n"
                + "".join(f"    {task}) names='{' '.join(names)}' ;;\n" for task, names in gate_tasks().items())
                + "    *) names=$1 ;;\n  esac\n  for n in $names; do echo \"> Task :$n\"; done\n"
                "else\n  echo 'CRAP FAIL com.example.X#y'\nfi\nexit $code\n")
failures = []
passed = 0


def sh(repo, *cmd, **kw):
    return subprocess.run(cmd, cwd=repo, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, **kw)


def git(repo, *args):
    r = sh(repo, "git", *args)
    assert r.returncode == 0, f"git {' '.join(args)}: {r.stdout}"
    return r.stdout.strip()


def make_repo():
    root = tempfile.mkdtemp(prefix="gauntlet-selftest-")
    repo = os.path.join(root, "service")
    bare = os.path.join(root, "origin.git")
    os.makedirs(repo)
    git(repo, "init", "-q", "-b", "main")
    git(repo, "config", "user.email", "t@example.com")
    git(repo, "config", "user.name", "t")
    shutil.copytree(KIT, os.path.join(repo, "gauntlet"), ignore=shutil.ignore_patterns("__pycache__"))
    # the layout under test decides `module`, not the installed kit's value (a monorepo install sets it)
    props = os.path.join(repo, "gauntlet/config/gauntlet.properties")
    with open(props) as f:
        lines = [line for line in f.read().splitlines() if not line.strip().startswith("module=")]
    with open(props, "w") as f:
        f.write("\n".join(lines) + f"\nmodule={','.join(MODULES)}\n")
    for prefix in [m + "/" for m in MODULES] or [""]:
        write(repo, prefix + "build.gradle.kts", 'plugins { java }\napply(from = "../gauntlet/gauntlet.gradle")\n')
        write(repo, prefix + "src/main/java/com/example/service/domain/Order.java",
              "package com.example.service.domain;\npublic class Order { public int discount(int p) { return p / 10; } }\n")
        write(repo, prefix + "src/test/java/com/example/service/domain/OrderTest.java",
              "package com.example.service.domain;\nclass OrderTest { }\n")
        write(repo, prefix + FEATURES_REL + "/.gitkeep", "")
        write(repo, prefix + "gradlew", FAKE_GRADLEW)
        os.chmod(os.path.join(repo, prefix + "gradlew"), 0o755)
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", "base")
    subprocess.run(["git", "init", "-q", "--bare", bare], check=True)
    git(repo, "remote", "add", "origin", bare)
    git(repo, "push", "-q", "origin", "main")
    git(repo, "checkout", "-q", "-b", "claude/feature-x")
    return repo


def write(repo, rel, text):
    path = os.path.join(repo, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def run_hook(repo, script, event, env=None, args=()):
    e = dict(os.environ, CLAUDE_PROJECT_DIR=repo)
    e.pop("GAUNTLET_GUARD", None)
    e.pop("GAUNTLET_STOP_HOOK", None)
    e.update(env or {})
    r = subprocess.run([sys.executable, os.path.join(repo, "gauntlet", "hooks", script), *args], cwd=repo,
                       input=json.dumps(event), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=e)
    return r


def guard(repo, agent, tool, **params):
    event = {"session_id": "s1", "cwd": repo, "tool_name": tool, "tool_input": params}
    if agent != "main":
        event["agent_type"] = agent
    r = run_hook(repo, "guard.py", event)
    if r.returncode != 0:
        return "CRASH " + r.stderr
    if not r.stdout.strip():
        return None
    return json.loads(r.stdout)["hookSpecificOutput"]["permissionDecisionReason"]


def check(name, condition, detail=""):
    global passed
    if condition:
        passed += 1
    else:
        failures.append(f"{name}  {detail}")


# The checker - the subagent that runs the read-only cards - has the reviewer's rights: every reviewer check runs for it too
TWINS = {"reviewer": ("checker",)}


def expect_deny(repo, name, agent, tool, **params):
    for who in (agent,) + TWINS.get(agent, ()):
        reason = guard(repo, who, tool, **params)
        check(name if who == agent else f"{name} [{who}]", reason is not None and not reason.startswith("CRASH"),
              f"expected deny, got {reason!r}")


def expect_allow(repo, name, agent, tool, **params):
    for who in (agent,) + TWINS.get(agent, ()):
        reason = guard(repo, who, tool, **params)
        check(name if who == agent else f"{name} [{who}]", reason is None, f"expected allow, got {reason!r}")


def guard_tests(repo):
    feature = FEATURES + "/order.feature"
    # --- human-owned files
    for path in ("gauntlet/config/gauntlet.properties", "gauntlet/gauntlet.gradle", "gauntlet/hooks/guard.py",
                 ".claude/settings.json", ".claude/agents/specifier.md", "CLAUDE.md", ".github/workflows/gauntlet.yml",
                 ".github/workflows/gauntlet-kit.yml"):
        expect_deny(repo, f"edit {path}", "main", "Edit", file_path=path, old_string="a", new_string="b")
    expect_deny(repo, "write absolute gauntlet path", "main", "Write",
                file_path=os.path.join(repo, "gauntlet/config/crap-allowlist.txt"), content="x")
    expect_allow(repo, "edit main code", "main", "Edit", file_path=M + "src/main/java/com/example/service/domain/Order.java",
                 old_string="a", new_string="b")
    expect_allow(repo, "edit build.gradle.kts", "main", "Edit", file_path=M + "build.gradle.kts", old_string="a", new_string="b")
    expect_allow(repo, "write step definitions", "main", "Write",
                 file_path=M + "src/acceptanceTest/java/com/example/service/acceptance/Steps.java", content="x")
    # --- shell writes to protected paths
    expect_deny(repo, "sed -i on gauntlet", "main", "Bash", command="sed -i 's/8/80/' gauntlet/config/gauntlet.properties")
    expect_deny(repo, "redirect into gauntlet", "main", "Bash", command="echo x > gauntlet/config/crap-allowlist.txt")
    expect_deny(repo, "cp over .claude", "main", "Bash", command="cp /tmp/s.json .claude/settings.json")
    expect_deny(repo, "python -c writing CLAUDE.md", "main", "Bash", command="python3 -c \"open('CLAUDE.md','w').write('')\"")
    expect_deny(repo, "git checkout gauntlet", "main", "Bash", command="git checkout -- gauntlet/config/checkstyle.xml")
    expect_deny(repo, "rm gauntlet dir", "main", "Bash", command="rm -rf gauntlet")
    expect_deny(repo, "rm the kit-files check of CI", "main", "Bash", command="rm .github/workflows/gauntlet-kit.yml")
    expect_deny(repo, "hook switch", "main", "Bash", command="GAUNTLET_GUARD=off ./gradlew gauntlet")
    expect_deny(repo, "export hook switch", "main", "Bash", command="export GAUNTLET_STOP_HOOK=off; ./gradlew test")
    # a command the guard cannot read is refused: a crashed or timed-out hook lets it run (review of 7061885)
    deep, hidden = "touch gauntlet/config/x", "touch gauntlet/config/x"
    for _ in range(100):
        deep = f"echo $({deep})"
    for _ in range(1000):
        hidden = f"echo ')' $({hidden})"      # the quoted ) defeats the nesting count; the reader overflows
    expect_deny(repo, "$( ... ) nested 100 levels deep", "main", "Bash", command=deep)
    expect_deny(repo, "$( ... ) nested 1000 levels deep behind quoted parentheses", "main", "Bash", command=hidden)
    shallow = "cat gauntlet/config/gauntlet.properties"
    for _ in range(10):
        shallow = f"echo $({shallow})"
    expect_allow(repo, "$( ... ) nested 10 levels deep that only reads", "main", "Bash", command=shallow)
    # --- no false positives
    expect_allow(repo, "gradlew gauntletFast", "main", "Bash", command="./gradlew gauntletFast --continue")
    expect_allow(repo, "gradlew gauntlet", "main", "Bash", command="./gradlew gauntlet --continue -Pgauntlet.baseRef=origin/main")
    expect_allow(repo, "cat gauntlet file", "main", "Bash", command="cat gauntlet/config/gauntlet.properties")
    expect_allow(repo, "git add -A", "main", "Bash", command="git add -A && git commit -m 'feat: discount'")
    expect_allow(repo, "git add gauntlet path", "main", "Bash", command="git add gauntlet/ .claude/ CLAUDE.md")
    expect_allow(repo, "commit mentioning gauntlet", "main", "Bash", command='git commit -m "chore: install the gauntlet"')
    expect_allow(repo, "git push own branch", "main", "Bash", command="git push -u origin HEAD")
    expect_allow(repo, "git push named branch", "main", "Bash", command="git push origin claude/feature-x")
    expect_allow(repo, "rm build/reports", "main", "Bash", command=f"rm -rf {M}build/reports")
    expect_allow(repo, "rm build/gauntlet (hook log dir)", "main", "Bash", command=f"rm -rf {M}build/gauntlet")
    expect_allow(repo, "tee into build/gauntlet", "main", "Bash", command=f"./gradlew gauntletFast 2>&1 | tee {M}build/gauntlet/run.log")
    expect_allow(repo, "cp hook log out", "main", "Bash", command=f"cp {M}build/gauntlet/gauntletFast-hook.log /tmp/")
    expect_allow(repo, "stash with gauntlet in message", "main", "Bash", command='git stash push -m "before gauntlet run"')
    expect_allow(repo, "mkdir build dir", "main", "Bash", command=f"mkdir -p {M}build/tmp && echo x > {M}build/tmp/a.txt")
    expect_allow(repo, "redirect 2>&1", "main", "Bash", command="./gradlew test 2>&1 | tail -20")
    expect_allow(repo, "redirect to /dev/null", "main", "Bash", command="./gradlew test > /dev/null 2>&1")
    expect_deny(repo, "quoted redirect into gauntlet", "main", "Bash", command='echo x > "gauntlet/config/crap-allowlist.txt"')
    expect_deny(repo, "heredoc into CLAUDE.md", "main", "Bash", command="cat > 'CLAUDE.md' <<EOF\nx\nEOF")
    expect_deny(repo, "2> into .claude", "main", "Bash", command="ls 2> .claude/settings.json")
    expect_deny(repo, "&> into .claude", "main", "Bash", command="ls &> .claude/settings.json")
    expect_deny(repo, "1> into gauntlet", "main", "Bash", command="ls 1> gauntlet/config/x")
    expect_deny(repo, "rm -rf star", "main", "Bash", command="rm -rf *")
    expect_deny(repo, "rm -rf dot", "main", "Bash", command="rm -rf .")
    expect_deny(repo, "rm glob under gauntlet", "main", "Bash", command="rm gauntlet/config/*.txt")
    expect_deny(repo, "cd into gauntlet then write", "main", "Bash", command="cd gauntlet/config && cp /tmp/x crap-allowlist.txt")
    expect_deny(repo, "git -C gauntlet checkout", "main", "Bash", command="git -C gauntlet checkout -- config/checkstyle.xml")
    expect_deny(repo, "write ~/.claude settings", "main", "Write", file_path=os.path.expanduser("~/.claude/settings.json"), content="{}")
    expect_deny(repo, "redirect into ~/.claude", "main", "Bash", command="echo '{\"disableAllHooks\": true}' > ~/.claude/settings.json")
    expect_deny(repo, "checkout -B to base", "main", "Bash", command="git checkout -B claude/feature-x origin/main")
    expect_deny(repo, "switch -C to base", "main", "Bash", command="git switch -C claude/feature-x origin/main")
    expect_deny(repo, "branch -f", "main", "Bash", command="git branch -f claude/feature-x origin/main")
    expect_deny(repo, "branch -D", "main", "Bash", command="git branch -D claude/feature-x")
    expect_allow(repo, "checkout -b new branch", "main", "Bash", command="git checkout -b claude/feature-z")
    expect_allow(repo, "switch existing", "main", "Bash", command="git switch main")
    expect_deny(repo, "gh -R pr merge", "main", "Bash", command="gh -R o/r pr merge 12 --squash")
    expect_deny(repo, "gh --repo pr merge", "main", "Bash", command="gh --repo o/r pr merge 12")
    # --- `gauntlet` as a Gradle task or under build/ is not the kit
    expect_allow(repo, "rm build/gauntlet log with $?", "main", "Bash", command=f"rm -f {M}build/gauntlet/x.log; echo $?")
    expect_allow(repo, "rm missing file then gradlew gauntlet", "main", "Bash",
                 command="rm -f out.log && ./gradlew gauntlet --continue > out.log 2>&1")
    expect_allow(repo, "gradlew gauntlet | tee $VAR", "main", "Bash", command='./gradlew gauntlet --continue 2>&1 | tee "$TMPDIR/run.log"')
    expect_allow(repo, "mkdir $VAR then gradlew gauntlet", "main", "Bash", command='mkdir -p "$SCRATCH" && ./gradlew gauntlet')
    expect_allow(repo, "python -c reads build/gauntlet", "main", "Bash",
                 command=f"python3 -c \"print(open('{M}build/gauntlet/gauntletFast-hook.log').read())\"")
    expect_deny(repo, "rm $VAR with gauntlet assigned", "main", "Bash", command="D=gauntlet; rm -rf $D")
    expect_deny(repo, "rm $VAR next to gradlew gauntlet", "main", "Bash", command="./gradlew gauntlet; X=gauntlet/config; rm -rf $X")
    expect_deny(repo, "echo gauntlet | xargs rm next to gradlew gauntlet", "main", "Bash",
                command="./gradlew gauntlet; echo gauntlet | xargs rm -rf")
    expect_deny(repo, "rm $VAR next to gradlew -p gauntlet", "main", "Bash", command="./gradlew -p gauntlet tasks; rm -rf $X")
    expect_deny(repo, "rm via build/.. back into gauntlet", "main", "Bash", command="rm -rf $P/build/../gauntlet")
    expect_deny(repo, "for ... do rm", "main", "Bash", command='for f in gauntlet/config/*; do rm "$f"; done')
    expect_deny(repo, "then rm", "main", "Bash", command="if true; then rm -rf gauntlet; fi")
    expect_deny(repo, "brace group rm", "main", "Bash", command="{ rm -rf .claude; }")
    expect_deny(repo, "negated rm", "main", "Bash", command="! rm -f CLAUDE.md")
    expect_deny(repo, "do sed -i", "main", "Bash", command="while false; do sed -i 's/8/80/' gauntlet/config/gauntlet.properties; done")
    expect_deny(repo, "then git push --force", "main", "Bash", command="if true; then git push --force; fi")
    expect_deny(repo, "rm via $PWD", "main", "Bash", command="rm -rf $PWD/gauntlet")
    expect_deny(repo, "rm via $CLAUDE_PROJECT_DIR", "main", "Bash", command='rm -rf "$CLAUDE_PROJECT_DIR/.claude"')
    expect_deny(repo, "xargs rm", "main", "Bash", command="git ls-files gauntlet/config | xargs rm -f")
    expect_deny(repo, "sed -i on substitution", "main", "Bash", command="sed -i 's/8/80/' $(git ls-files gauntlet/config/gauntlet.properties)")
    expect_deny(repo, "pushd then sed", "main", "Bash", command="pushd gauntlet/config && sed -i 's/8/80/' gauntlet.properties")
    expect_allow(repo, "mkdir via $CLAUDE_PROJECT_DIR in tests", "hardener", "Bash", command=f'mkdir -p "$CLAUDE_PROJECT_DIR/{M}src/test/java/com/example/api"')
    expect_allow(repo, "commit message heredoc mentioning install", "main", "Bash",
                 command='git commit -m "$(cat <<\'EOF\'\nchore: install the gauntlet hooks, roles and the CI workflow\n\nrm nothing; patch nothing.\nEOF\n)"')
    expect_allow(repo, "heredoc report into /tmp", "main", "Bash", command="cat > /tmp/report.md <<'EOF'\n- gauntlet/README.md -> see\nrm -rf gauntlet\nEOF")
    expect_deny(repo, "heredoc opener still checked", "main", "Bash", command="cat > gauntlet/config/x <<'EOF'\nx\nEOF")
    expect_allow(repo, "hardener heredoc test with lambda", "hardener", "Bash",
                 command=f"cat > {M}src/test/java/com/example/service/domain/OrderTest.java <<'EOF'\nassertTrue(discount(100) >= 10); list.forEach(x -> check(x));\nEOF")
    expect_allow(repo, "hardener cleans pit history", "hardener", "Bash", command=f"rm -rf {M}build/pitHistory.txt {M}build/reports/pitest")
    expect_allow(repo, "hardener tee into build", "hardener", "Bash", command=f"./gradlew mutationGate --continue 2>&1 | tee {M}build/pit.log")
    expect_allow(repo, "specifier tee into build", "specifier", "Bash", command=f"./gradlew acceptanceTest 2>&1 | tee {M}build/acceptance.log")
    expect_deny(repo, "git revert", "main", "Bash", command="git revert --no-edit HEAD")
    expect_allow(repo, "rm -rf build via var", "main", "Bash", command='rm -rf "$CLAUDE_PROJECT_DIR/build"')
    expect_deny(repo, "gh api graphql automerge", "main", "Bash",
                command="gh api graphql -f query='mutation { enablePullRequestAutoMerge(input: {pullRequestId: \"x\"}) { clientMutationId } }'")
    expect_allow(repo, "gh pr create", "main", "Bash", command='gh pr create --title "feat" --body-file /tmp/report.md')
    expect_allow(repo, "gh pr view", "main", "Bash", command="gh pr view --json url")
    expect_allow(repo, "git reset unstage", "main", "Bash", command=f"git reset HEAD {M}build.gradle.kts")
    expect_allow(repo, "git reset plain", "main", "Bash", command="git reset")
    expect_allow(repo, "git reset path", "main", "Bash", command=f"git reset {M}build.gradle.kts")
    expect_allow(repo, "git commit -am", "main", "Bash", command='git commit -am "msg no verify words"')
    # --- human-only git and gh
    expect_deny(repo, "commit --amend", "main", "Bash", command='git commit --amend -m "x"')
    expect_deny(repo, "commit -n", "main", "Bash", command='git commit -anm "x"')
    expect_deny(repo, "no-verify", "main", "Bash", command="git push --no-verify")
    expect_deny(repo, "rebase", "main", "Bash", command="git rebase -i origin/main")
    expect_deny(repo, "push --force", "main", "Bash", command="git push --force origin claude/feature-x")
    expect_deny(repo, "push -f", "main", "Bash", command="git push -f")
    expect_deny(repo, "push force-with-lease", "main", "Bash", command="git push --force-with-lease")
    expect_deny(repo, "push to main", "main", "Bash", command="git push origin main")
    expect_deny(repo, "push HEAD:main", "main", "Bash", command="git push origin HEAD:main")
    expect_deny(repo, "push +refspec", "main", "Bash", command="git push origin +claude/feature-x")
    expect_deny(repo, "reset --hard revision", "main", "Bash", command="git reset --hard origin/main")
    expect_deny(repo, "reset HEAD~1", "main", "Bash", command="git reset HEAD~1")
    expect_deny(repo, "reset --soft", "main", "Bash", command="git reset --soft HEAD^")
    expect_deny(repo, "filter-branch", "main", "Bash", command="git filter-branch --tree-filter 'rm -f x' HEAD")
    expect_deny(repo, "update-ref", "main", "Bash", command="git update-ref refs/heads/claude/feature-x abc123")
    expect_deny(repo, "gh pr merge", "main", "Bash", command="gh pr merge 12 --squash")
    expect_deny(repo, "gh api merge", "main", "Bash", command="gh api -X PUT repos/o/r/pulls/12/merge")
    expect_deny(repo, "gh merge in chain", "main", "Bash", command="./gradlew gauntlet && gh pr merge --auto")
    # --- hardening: human-only rules inside wrappers and inline code
    expect_deny(repo, "bash -c force push", "main", "Bash", command="bash -c 'git push --force origin main'")
    expect_deny(repo, "sh -c no-verify commit", "main", "Bash", command="sh -c 'git commit --no-verify -m x'")
    expect_deny(repo, "eval force push", "main", "Bash", command="eval 'git push --force'")
    expect_deny(repo, "env -i force push", "main", "Bash", command="env -i git push --force")
    expect_deny(repo, "sudo -u force push", "main", "Bash", command="sudo -u root git push --force")
    expect_deny(repo, "command -p force push", "main", "Bash", command="command -p git push --force")
    expect_deny(repo, "echo force push | bash", "main", "Bash", command="echo 'git push --force origin main' | bash")
    expect_deny(repo, "bash -c gh pr merge", "main", "Bash", command="bash -c 'gh pr merge 12'")
    expect_deny(repo, "env assignment then force push", "main", "Bash", command="env FOO=bar git push --force")
    expect_deny(repo, "env -S force push", "main", "Bash",
                command="env -S 'git push --force origin main'")
    expect_deny(repo, "env --split-string attached force push", "main", "Bash",
                command="env --split-string='git push --force origin main'")
    expect_deny(repo, "sudo env -S nested force push", "main", "Bash",
                command="sudo env -S 'git push --force origin main'")
    expect_allow(repo, "env -S harmless", "main", "Bash", command="env -S 'printf hello'")
    expect_deny(repo, "python os.system nested force push", "main", "Bash",
                command="python3 -c \"import os; os.system('git push --force')\"")
    expect_deny(repo, "python subprocess nested gh merge", "main", "Bash",
                command="python3 -c \"import subprocess; subprocess.run('gh pr merge 12', shell=True)\"")
    expect_deny(repo, "node child_process nested force push", "main", "Bash",
                command="node -e \"require('child_process').execSync('git push --force')\"")
    expect_deny(repo, "bash -c multi-command inline force push", "main", "Bash",
                command="bash -c 'echo ok; git push --force origin main'")
    expect_deny(repo, "sh -c inline amend after echo", "main", "Bash",
                command="sh -c 'echo hi && git commit --amend'")
    expect_deny(repo, "bash -c multi-command inline gh merge", "main", "Bash",
                command="bash -c 'echo ok; gh pr merge 12'")
    expect_allow(repo, "bash -c multi-command harmless", "main", "Bash",
                 command="bash -c 'echo ok; echo done'")
    expect_allow(repo, "python -c harmless quoted text", "main", "Bash",
                 command="python3 -c \"print('hello; world')\"")
    expect_allow(repo, "bash -c harmless", "main", "Bash", command="bash -c 'echo hello'")
    expect_deny(repo, "double-quote command substitution force push", "main", "Bash",
                command='echo "$(git push --force origin main)"')
    expect_deny(repo, "double-quote backtick amend", "main", "Bash",
                command='echo "`git commit --amend`"')
    expect_allow(repo, "single-quote substitution is literal", "main", "Bash",
                 command="echo '$(git push --force origin main)'")
    expect_allow(repo, "double-quote harmless substitution", "main", "Bash",
                 command='git commit -m "release $(date)"')
    # --- hardening (Grok): unquoted pipe payload, quote-aware substitution depth, wider inline interpreters
    expect_deny(repo, "echo unquoted force push | bash", "main", "Bash",
                command="echo git push --force origin main | bash")
    expect_deny(repo, "echo unquoted force push | sh", "main", "Bash", command="echo git push --force | sh")
    expect_deny(repo, "printf unquoted force push | bash", "main", "Bash",
                command="printf %s git\\ push\\ --force | bash")
    expect_allow(repo, "echo harmless | bash", "main", "Bash", command="echo hello | bash")
    expect_allow(repo, "printf harmless | bash", "main", "Bash", command="printf '%s\\n' hello | bash")
    expect_deny(repo, "substitution depth past quoted paren", "main", "Bash",
                command='echo "$(python3 -c \'print(")")\'; git push --force)"')
    expect_allow(repo, "echo harmless substitution", "main", "Bash", command='echo "$(date)"')
    expect_deny(repo, "node --eval force push", "main", "Bash",
                command="node --eval \"require('child_process').execSync('git push --force')\"")
    expect_deny(repo, "node --eval= force push", "main", "Bash",
                command="node --eval=\"require('child_process').execSync('git push --force')\"")
    expect_deny(repo, "python3.12 -c force push", "main", "Bash",
                command="python3.12 -c \"import os; os.system('git push --force')\"")
    expect_deny(repo, "python subprocess argv force push", "main", "Bash",
                command="python3 -c \"import subprocess; subprocess.run(['git','push','--force'])\"")
    expect_deny(repo, "perl -E force push", "main", "Bash", command="perl -E 'system(\"git push --force\")'")
    expect_allow(repo, "node --eval harmless", "main", "Bash", command="node --eval \"console.log(1)\"")
    expect_allow(repo, "python3.12 -c harmless", "main", "Bash", command="python3.12 -c \"print(1)\"")
    expect_allow(repo, "perl -E harmless", "main", "Bash", command="perl -E 'say 1'")
    expect_allow(repo, "python data list not a command", "main", "Bash",
                 command="python3 -c \"print(['git','push'])\"")
    # --- F14: an argv list is joined and re-parsed as shell; echo DATA must not become a command.
    #          shlex.quote keeps each element intact so '; git rebase' inside an echo arg stays text.
    expect_allow(repo, "F14 echo argv semicolon data", "main", "Bash",
                 command="python3 -c \"import subprocess; subprocess.run(['echo', 'example; git rebase'])\"")
    expect_allow(repo, "F14 echo argv pipe data", "main", "Bash",
                 command="python3 -c \"import subprocess; subprocess.run(['echo', 'x | git push --force'])\"")
    expect_allow(repo, "F14 echo argv gh merge data", "main", "Bash",
                 command="python3 -c \"import subprocess; subprocess.run(['echo', 'gh pr merge 1'])\"")
    expect_allow(repo, "F14 data args after python -c program", "main", "Bash",
                 command="python3 -c \"print('hi')\" 'example; git rebase'")
    expect_allow(repo, "F14 option-looking data after python -c program", "main", "Bash",
                 command="python3 -c 'import sys; print(sys.argv[1:])' -e 'git rebase'")
    expect_allow(repo, "option-looking data after python script", "main", "Bash",
                 command="python3 docsearch.py -e 'git rebase'")
    expect_allow(repo, "option-looking data after python module", "main", "Bash",
                 command="python3 -m docsearch -e 'git rebase'")
    expect_allow(repo, "reviewer python script option is read-only", "reviewer", "Bash",
                 command="python3 docsearch.py -e price")
    expect_deny(repo, "python option value does not hide inline code", "main", "Bash",
                command="python3 -W ignore -c \"import os; os.system('git push --force')\"")
    expect_deny(repo, "F14 control real force push argv", "main", "Bash",
                command="python3 -c \"import subprocess; subprocess.run(['git', 'push', '--force'])\"")
    expect_deny(repo, "F14 control real gh merge argv", "main", "Bash",
                command="python3 -c \"import subprocess; subprocess.run(['gh', 'pr', 'merge', '1'])\"")
    # --- V5b: a quoted string that merely LOOKS like a command is data unless an exec-style call runs it
    expect_allow(repo, "print literal force push is data", "main", "Bash",
                 command="python3 -c \"print('git push --force')\"")
    expect_allow(repo, "console.log literal force push is data", "main", "Bash",
                 command="node -e \"console.log('git push --force')\"")
    expect_allow(repo, "perl print literal force push is data", "main", "Bash",
                 command="perl -e \"print 'git push --force'\"")
    # --- V5c: text piped to an interpreter running a FILE/MODULE/inline code is data, not shell commands;
    #          only a stdin program (bare, '-', shell -s) executes the piped text
    expect_allow(repo, "pipe force push to python script file", "main", "Bash",
                 command="echo 'git push --force' | python3 script.py")
    expect_allow(repo, "pipe force push to node script file", "main", "Bash",
                 command="echo 'git push --force' | node app.js")
    expect_allow(repo, "pipe force push to python module", "main", "Bash",
                 command="echo 'git push --force' | python3 -m json.tool")
    expect_allow(repo, "pipe force push to bash inline code", "main", "Bash",
                 command="echo 'git push --force' | bash -c 'echo hi'")
    expect_deny(repo, "pipe force push to bash -s", "main", "Bash",
                command="echo 'git push --force' | bash -s")
    expect_deny(repo, "pipe force push to python stdin dash", "main", "Bash",
                command="echo 'git push --force' | python3 -")
    expect_allow(repo, "sudo gradlew", "main", "Bash", command="sudo -u root ./gradlew test")
    # --- hardening: .git is human-owned (deleting it disables the stop hook and unfreezes the spec)
    expect_deny(repo, "rm -rf .git", "main", "Bash", command="rm -rf .git")
    expect_deny(repo, "write .git/config", "main", "Write", file_path=".git/config", content="x")
    expect_deny(repo, "forge green state", "main", "Bash",
                command="echo x > .git/gauntlet-hook/last-green-gauntletFast")
    expect_allow(repo, "commit still works with .git protected", "main", "Bash", command="git commit -m 'feat: x'")
    # --- hardening: push forms that rewrite every ref or the base branch
    expect_deny(repo, "push --mirror", "main", "Bash", command="git push --mirror origin")
    expect_deny(repo, "push --all", "main", "Bash", command="git push --all origin")
    cur = git(repo, "symbolic-ref", "--short", "HEAD")
    git(repo, "switch", "-q", "main")
    expect_deny(repo, "push HEAD while on main", "main", "Bash", command="git push origin HEAD")
    expect_deny(repo, "push no refspec while on main", "main", "Bash", command="git push origin")
    expect_deny(repo, "push bare while on main", "main", "Bash", command="git push")
    expect_deny(repo, "push @ while on main", "main", "Bash", command="git push origin @")
    expect_deny(repo, 'push without a refspec and a redirection while on main', "main", "Bash", command='git push origin 2>&1')
    expect_deny(repo, 'push without a refspec through a pipe while on main', "main", "Bash", command='git push origin 2>&1 | tail -3')
    expect_deny(repo, 'push without a refspec, stdout dropped, while on main', "main", "Bash", command='git push origin >/dev/null')
    expect_deny(repo, 'push after cd to the toplevel while on main', "main", "Bash", command='cd "$(git rev-parse --show-toplevel)" && git push')
    expect_deny(repo, "push @:main while on main", "main", "Bash", command="git push origin @:main")
    expect_allow(repo, "push HEAD:other while on main", "main", "Bash", command="git push origin HEAD:other")
    expect_allow(repo, "push @:other while on main", "main", "Bash", command="git push origin @:other")
    git(repo, "switch", "-q", cur)
    expect_allow(repo, "push HEAD on feature branch", "main", "Bash", command="git push -u origin HEAD")
    expect_allow(repo, "push no refspec on feature branch", "main", "Bash", command="git push origin")
    expect_allow(repo, "push bare on feature branch", "main", "Bash", command="git push")
    expect_allow(repo, "push @ on feature branch", "main", "Bash", command="git push origin @")
    # --- hardening (Grok): a worktree on the base branch is pushed even from a feature-branch session
    root = os.path.dirname(repo)
    wt_main = os.path.join(root, "wt-main")
    wt_feat = os.path.join(root, "wt-feat")
    git(repo, "worktree", "add", "-q", wt_main, "main")
    git(repo, "worktree", "add", "-q", "-b", "claude/wt-feature", wt_feat)
    expect_deny(repo, "push -C worktree HEAD on main", "main", "Bash", command=f"git -C {wt_main} push origin HEAD")
    expect_deny(repo, "push -C worktree no refspec on main", "main", "Bash", command=f"git -C {wt_main} push")
    expect_deny(repo, "push -C worktree @ on main", "main", "Bash", command=f"git -C {wt_main} push origin @")
    expect_allow(repo, "push -C worktree HEAD on feature", "main", "Bash", command=f"git -C {wt_feat} push origin HEAD")
    # --- V5a: `cd worktree && git push` judges the branch of THAT worktree (follow_cd), not the session's project
    expect_allow(repo, "cd feature worktree push HEAD", "main", "Bash", command=f"cd {wt_feat} && git push origin HEAD")
    expect_allow(repo, "cd feature worktree push no refspec", "main", "Bash", command=f"cd {wt_feat} && git push")
    expect_deny(repo, "cd main worktree push HEAD", "main", "Bash", command=f"cd {wt_main} && git push origin HEAD")
    expect_deny(repo, "cd main worktree push no refspec", "main", "Bash", command=f"cd {wt_main} && git push")
    expect_allow(repo, "pushd feature worktree push HEAD", "main", "Bash", command=f"pushd {wt_feat} && git push origin HEAD")
    expect_allow(repo, "cd relative feature worktree push HEAD", "main", "Bash", command="cd ../wt-feat && git push origin HEAD")
    # --- the `)` of a case pattern does not end the subshell: the push still happens in the worktree on main
    expect_deny(repo, "case pattern inside a subshell keeps its directory", "main", "Bash",
                command=f"( cd {wt_main} ; case x in x) git push -u origin HEAD ;; esac )")
    # --- another worktree of the repository holds the same human-owned files: its gauntlet/ is protected too
    expect_deny(repo, "worktree: rm the gauntlet of another worktree", "main", "Bash", command=f"rm -rf {wt_feat}/gauntlet/hooks")
    expect_deny(repo, "worktree: cd there and append to its thresholds", "main", "Bash",
                command=f"cd {wt_feat} && echo x >> gauntlet/config/gauntlet.properties")
    expect_deny(repo, "worktree: edit its thresholds", "main", "Edit",
                file_path=os.path.join(wt_feat, "gauntlet/config/gauntlet.properties"), old_string="a", new_string="b")
    expect_deny(repo, "worktree: reviewer writes there", "reviewer", "Bash", command=f"cd {wt_feat} && echo x > notes.txt")
    expect_allow(repo, "worktree: ordinary file there", "main", "Bash", command=f"cd {wt_feat} && echo x > notes.txt")
    expect_allow(repo, "worktree: its build directory", "main", "Bash", command=f"rm -rf {wt_feat}/build")
    expect_allow(repo, "worktree: a directory that is no worktree", "main", "Bash", command="echo x > ../elsewhere-gauntlet-notes.txt")
    # --- F10: a `cd` INSIDE a subshell ( ) or command-substitution $( ) must not leak to the parent shell,
    #          so a parent push of the session's own feature branch is judged against the project, not mainwt.
    expect_allow(repo, "F10 subshell cd to main does not leak", "main", "Bash",
                 command=f"( cd {wt_main} && git status ) && git push -u origin HEAD")
    expect_allow(repo, "F10 cmdsubst cd to main does not leak", "main", "Bash",
                 command=f"echo $( cd {wt_main} && git status ) && git push -u origin HEAD")
    expect_allow(repo, "F10 nested subshell cd does not leak", "main", "Bash",
                 command=f"( cd {wt_main} && ( git status ) ) && git push -u origin HEAD")
    expect_deny(repo, "F10 control push inside subshell on main", "main", "Bash",
                command=f"( cd {wt_main} && git push -u origin HEAD )")
    expect_deny(repo, "F10 control real cd to main then push", "main", "Bash",
                command=f"cd {wt_main} && git push -u origin HEAD")
    expect_deny(repo, "F10 control cd then subshell then push", "main", "Bash",
                command=f"cd {wt_main} && ( git status ) && git push -u origin HEAD")
    for name, project, event_cwd, expected in (
            ("F10 event cwd feature overrides main project", wt_main, wt_feat, False),
            ("F10 event cwd main overrides feature project", wt_feat, wt_main, True)):
        event = {"session_id": "s1", "cwd": event_cwd, "tool_name": "Bash",
                 "tool_input": {"command": "git push origin HEAD"}}
        r = run_hook(repo, "guard.py", event, env={"CLAUDE_PROJECT_DIR": project})
        check(name, r.returncode == 0 and bool(r.stdout.strip()) == expected, r.stdout + r.stderr)
    git(repo, "worktree", "remove", "--force", wt_main)   # free main again for the later checkout-main tests
    git(repo, "worktree", "remove", "--force", wt_feat)
    # --- hardening: hook-skipping abbreviations and config
    expect_deny(repo, "no-verify abbreviation", "main", "Bash", command="git commit --no-verif -m x")
    expect_deny(repo, "core.hooksPath off", "main", "Bash", command="git -c core.hooksPath=/dev/null commit -m x")
    expect_deny(repo, "core.hooksPath attached -c", "main", "Bash", command="git -ccore.hooksPath=/dev/null commit -m x")
    expect_allow(repo, "core.hooksPath in commit message", "main", "Bash",
                 command="git commit -m 'Document core.hooksPath behavior'")
    expect_allow(repo, "core.hooksPath assignment in message", "main", "Bash",
                 command="git commit -m 'core.hooksPath=/dev/null is bad'")
    expect_allow(repo, "no-verify words in message", "main", "Bash",
                 command='git commit -m "docs: mention --no-verify in the message"')
    # --- hardening: git resolves every unambiguous prefix of a long option (`--amen` amends, `--mirro` mirrors),
    #     takes the branch name attached to -B/-C or inside a short cluster, and knows --branches as --all
    for name, command in (
            ("commit --amen", "git commit --amen -m x"),
            ("commit --am", "git commit --am -m x"),
            ("commit --ame after the message", 'git commit -m "x" --ame'),
            ("bash -c commit --amen", "bash -c 'git commit --amen -m x'"),
            ("push --mirro", "git push --mirro origin"),
            ("push --m", "git push --m origin"),
            ("push --al", "git push --al origin"),
            ("push --branches (alias of --all)", "git push --branches origin"),
            ("push --b", "git push --b origin"),
            ("push --forc", "git push --forc origin HEAD"),
            ("push --for", "git push --for origin HEAD"),
            ("push --force-with-leas", "git push --force-with-leas origin HEAD"),
            ("branch --forc", "git branch --forc claude/feature-x origin/main"),
            ("branch --fo", "git branch --fo claude/feature-x origin/main"),
            ("branch --delet", "git branch --delet scratch"),
            ("branch --d", "git branch --d scratch"),
            ("branch --mov", "git branch --mov a b"),
            ("branch --cop", "git branch --cop a b"),
            ("switch --force-creat", "git switch --force-creat claude/feature-x origin/main"),
            ("switch --force-", "git switch --force- claude/feature-x origin/main"),
            ("checkout -qB cluster", "git checkout -qB claude/feature-x origin/main"),
            ("checkout -Bname attached", "git checkout -Bclaude/feature-x origin/main"),
            ("switch -fC cluster", "git switch -fC claude/feature-x origin/main"),
            ("switch -Cname attached", "git switch -Cclaude/feature-x origin/main")):
        expect_deny(repo, "abbreviation: " + name, "main", "Bash", command=command)
    for name, command in (
            ("commit message --am", 'git commit -m "--am"'),
            ("commit cluster message --amend", 'git commit -am "--amend"'),
            ("commit message mentions --amend", 'git commit -m "docs: --amend is forbidden"'),
            ("commit pathspec after --", "git commit -m x -- --amend"),
            ("commit --allow-empty", "git commit --allow-empty -m x"),
            ("commit --all", "git commit --all -m x"),
            ("commit --author", 'git commit --author="A <a@b.c>" -m x'),
            ("commit --no-amend", "git commit --no-amend -m x"),
            ("push --follow-tags", "git push --follow-tags origin HEAD"),
            ("push --fol", "git push --fol origin HEAD"),
            ("push --atomic", "git push --atomic origin HEAD"),
            ("push --at", "git push --at origin HEAD"),
            ("branch --merged", "git branch --merged"),
            ("branch --me", "git branch --me"),
            ("branch --no-merged", "git branch --no-merged"),
            ("branch --format", "git branch --format='%(refname)'"),
            ("branch --form", "git branch --form '%(refname)'"),
            ("branch --contains", "git branch --contains HEAD"),
            ("branch --column", "git branch --column"),
            ("branch --create-reflog", "git branch --create-reflog tmp"),
            ("checkout -bBugfix", "git checkout -bBugfix"),
            ("checkout -q -b", "git checkout -q -b tmp"),
            ("switch -cCleanup", "git switch -cCleanup"),
            ("switch --create", "git switch --create tmp"),
            ("switch --cr", "git switch --cr tmp"),
            ("switch --force (discard changes, no re-creation)", "git switch --force claude/feature-x")):
        expect_allow(repo, "abbreviation control: " + name, "main", "Bash", command=command)
    expect_allow(repo, "reviewer branch --merged", "reviewer", "Bash", command="git branch --merged")
    expect_allow(repo, "reviewer branch --format", "reviewer", "Bash", command="git branch --format='%(refname)'")
    # --- over-block: the VALUE of an option is not a cluster of flags (`--sort -committerdate` has c, m and d in it;
    #     `-uno` is --untracked-files=no, not -n)
    for name, command in (
            ("branch --sort -committerdate", "git branch --sort -committerdate"),
            ("branch --sort=-committerdate", "git branch --sort=-committerdate"),
            ("branch -r --sort -committerdate", "git branch -r --sort -committerdate"),
            ("commit -uno", 'git commit -uno -m "feat: y"'),
            ("commit -S", 'git commit -S -m "feat: y"'),
            ("commit -Sname", 'git commit -Sname -m "feat: y"')):
        expect_allow(repo, "option value: " + name, "main", "Bash", command=command)
    expect_allow(repo, "option value: reviewer branch --sort -committerdate", "reviewer", "Bash",
                 command="git branch --sort -committerdate")
    expect_allow(repo, "option value: reviewer branch --contains HEAD", "reviewer", "Bash",
                 command="git branch --contains HEAD")
    for name, command in (
            ("branch --sort -committerdate -D", "git branch --sort -committerdate -D scratch"),
            ("commit -n", "git commit -n -m x"),
            ("commit -an", "git commit -an -m x"),
            ("commit -uno -n", "git commit -uno -n -m x")):
        expect_deny(repo, "option value control: " + name, "main", "Bash", command=command)
    expect_deny(repo, "option value control: reviewer branch --contains HEAD -D", "reviewer", "Bash",
                command="git branch --contains HEAD -D scratch")
    # --- carriers: every rule also holds inside shell code that another program runs. A shell takes -c inside a
    #     cluster or behind option values (bash -lc, sh -ec, bash -euo pipefail -c); a wrapper is the same program
    #     under its full path and may take option values (nice -n 10, timeout -s KILL 60, xargs -n 1); su -c,
    #     find -exec and every spelling of env -S hand a command line on
    push = "git push --force origin HEAD"
    for name, command in (
            ("bash -lc", f"bash -lc '{push}'"),
            ("bash -lc amend", 'bash -lc "git commit --amend -m x"'),
            ("sh -ec", f"sh -ec '{push}'"),
            ("bash -xc", f"bash -xc '{push}'"),
            ("bash -euo pipefail -c", f"bash -euo pipefail -c '{push}'"),
            ("bash -o pipefail -c", f"bash -o pipefail -c '{push}'"),
            ("bash --login -c", f"bash --login -c '{push}'"),
            ("dash -c", f"dash -c '{push}'"),
            ("ksh -c", f"ksh -c '{push}'"),
            ("zsh -ic", f"zsh -ic '{push}'"),
            ("/usr/bin/env", f"/usr/bin/env {push}"),
            ("/usr/bin/sudo", f"/usr/bin/sudo {push}"),
            ("/usr/bin/time", f"/usr/bin/time {push}"),
            ("nice -n 10", f"nice -n 10 {push}"),
            ("timeout -s KILL 60", f"timeout -s KILL 60 {push}"),
            ("xargs -n 1", f"xargs -n 1 {push}"),
            ("setsid", f"setsid {push}"),
            ("stdbuf -oL", f"stdbuf -oL {push}"),
            ("env -S attached", f"env -S'{push}'"),
            ("env -iS", f"env -iS '{push}'"),
            ("env --split-s=", f"env --split-s='{push}'"),
            ("su -c", f"su -c '{push}'"),
            ("find -exec git", f"find . -maxdepth 0 -exec {push} \\;"),
            ("find -exec sh -c", f"find . -maxdepth 0 -exec sh -c '{push}' \\;"),
            ("substitution after an escaped quote", 'echo "\\"\'" "$(' + push + ')"'),
            ("substitution in a message with escaped quotes", 'git commit -m "say \\"don\'t\\" $(' + push + ')"'),
            ("substitution after an ANSI-C string", "echo $'it\\'s' \"$(" + push + ")\""),
            ("nested escaped backticks", 'echo "`echo \\`' + push + '\\``"'),
            ("pipe into bash -o pipefail", f"echo '{push}' | bash -o pipefail")):
        expect_deny(repo, "carrier: " + name, "main", "Bash", command=command)
    for name, command in (
            ("bash -lc redirect", "bash -lc 'echo x > gauntlet/config/gauntlet.properties'"),
            ("bash -lc rm", "bash -lc 'rm -rf gauntlet'"),
            ("bash -lc cd then rm", "bash -lc 'cd gauntlet && rm -f config/gauntlet.properties'"),
            ("dash -c settings", "dash -c 'rm -f .claude/settings.json'"),
            ("/usr/bin/env rm", "/usr/bin/env rm -rf gauntlet"),
            ("/usr/bin/sudo rm", "/usr/bin/sudo rm -rf gauntlet"),
            ("nice -n 10 rm", "nice -n 10 rm -rf gauntlet"),
            ("timeout -s KILL 5 rm", "timeout -s KILL 5 rm -rf gauntlet"),
            ("xargs -n 1 rm", "xargs -n 1 rm -rf gauntlet"),
            ("setsid rm", "setsid rm -rf gauntlet"),
            ("su -c rm", "su -c 'rm -rf gauntlet'"),
            ("find -exec sh -c rm", "find . -maxdepth 0 -exec sh -c 'rm -rf gauntlet' \\;"),
            ("eval rm", "eval 'rm -rf gauntlet'"),
            ("pipe rm into bash", "echo 'rm -rf gauntlet' | bash"),
            ("pipe rm into bash -o pipefail", "echo 'rm -rf gauntlet' | bash -o pipefail"),
            ("rm in a quoted substitution", 'echo "$(rm -rf gauntlet)"'),
            ("sed -i in an assignment substitution", 'x="$(sed -i s/8/80/ gauntlet/config/gauntlet.properties)"'),
            ("bash -c inside bash -c", "bash -c \"bash -lc 'rm -rf gauntlet'\"")):
        expect_deny(repo, "carrier writes the gauntlet: " + name, "main", "Bash", command=command)
    main_java = M + "src/main/java/com/example/service/domain/Order.java"
    test_java = M + "src/test/java/com/example/service/domain/OrderTest.java"
    expect_deny(repo, "carrier role: reviewer commits through bash -lc", "reviewer", "Bash", command="bash -lc 'git commit -m x'")
    expect_deny(repo, "carrier role: reviewer writes through bash -lc", "reviewer", "Bash", command="bash -lc 'echo x > notes.txt'")
    expect_deny(repo, "carrier role: hardener writes main code through bash -lc", "hardener", "Bash",
                command=f"bash -lc 'echo x > {main_java}'")
    expect_allow(repo, "carrier role: hardener writes test code through bash -lc", "hardener", "Bash",
                 command=f"bash -lc 'echo x > {test_java}'")
    expect_allow(repo, "carrier role: reviewer reads through bash -lc", "reviewer", "Bash", command="bash -lc 'git status'")
    for name, command in (
            ("bash -lc echo", "bash -lc 'echo hello'"),
            ("bash -euo pipefail -c gradle", "bash -euo pipefail -c 'echo ok; ./gradlew gauntletFast'"),
            ("bash -e script", "bash -e script.sh"),
            ("sh -c status", "sh -c 'git status'"),
            ("/usr/bin/env python3", "/usr/bin/env python3 --version"),
            ("nice gradle", "nice -n 10 ./gradlew test"),
            ("timeout gradle", "timeout 60 ./gradlew test"),
            ("xargs echo", "xargs -n 1 echo"),
            ("find -exec grep", "find . -name '*.java' -exec grep -l push {} \\;"),
            ("su -c echo", "su -c 'echo hi'"),
            ("escaped quotes only", 'echo "\\"it\'s\\" fine"'),
            ("push text quoted for another shell's pipe", "echo 'git push --force | bash -o pipefail'"),
            ("read the gauntlet through bash -lc", "bash -lc 'cat gauntlet/README.md'"),
            ("grep the gauntlet through bash -c", "bash -c 'grep -n threshold gauntlet/config/gauntlet.properties'"),
            ("the gauntlet task through bash -lc", "bash -lc './gradlew gauntlet'"),
            ("find in the gauntlet -exec grep", "find gauntlet -name '*.py' -exec grep -n foo {} \\;"),
            ("pipe a read into sh", "echo 'cat gauntlet/README.md' | sh"),
            ("read in a substitution", 'echo "$(cat gauntlet/README.md | head -1)"'),
            ("clean the build through bash -lc", "bash -lc 'rm -rf build'")):
        expect_allow(repo, "carrier control: " + name, "main", "Bash", command=command)
    # --- carriers, continued: the payload may sit behind another command, a filter or `|&`, and a substitution
    #     opens a quoting context of its own (a quoted `)` or a case pattern inside it does not end the outer one)
    for name, command in (
            ('a command before the echo', 'true && echo git push --force origin main | bash'),
            ('cd before the echo', 'cd . && echo git push --force origin main | bash'),
            ('another command; echo', 'git status; echo git push --force origin main | bash'),
            ('a subshell feeds the pipe', '(echo git push --force origin main) | bash'),
            ('a filter between echo and the shell', "echo 'git push --force origin main' | cat | bash"),
            ('the |& pipe', "echo 'git push --force origin main' |& bash"),
            ('a shell with several option words', "echo 'git push --force origin main' | bash -e -x"),
            ('the pipe inside if ... fi', "if true; then echo 'git push --force origin main' | bash; fi"),
            ('unquoted rm piped into sh', 'echo rm -rf gauntlet | sh'),
            ('a command before the piped rm', 'true && echo rm -rf gauntlet | sh'),
            ('a quoted ) inside a nested substitution', 'echo "$(echo "$(echo ")")"; git push --force origin main 2>&1)"'),
            ('an ANSI-C quoted ) inside a substitution', 'echo "$(echo $\'\\\')\'; git push --force origin main 2>&1)"'),
            ('a case pattern inside a substitution', 'echo "$(case x in x) git push --force origin main 2>&1;; esac)"'),
            ('rm after a nested substitution', 'echo "$(echo "$(echo ")")"; rm -rf gauntlet)"')):
        expect_deny(repo, "carrier: " + name, "main", "Bash", command=command)
    for name, command in (
            ('harmless echo piped into bash', 'true && echo hello | bash'),
            ('git status through a filter', "echo 'git status' | cat | bash"),
            ('git status after a nested substitution', 'echo "$(echo "$(echo ")")"; git status)"'),
            ('a harmless case inside a substitution', 'echo "$(case x in x) echo y;; esac)"'),
            ('git log piped into head', "git log --format='%h %s' | head -3"),
            ('git status piped into cat', 'git status | cat'),
            ('a quoted pipe character', "echo 'a|b' | tr '|' ' '"),
            ('grep for the words', "cat notes.txt | grep 'git push --force'")):
        expect_allow(repo, "carrier control: " + name, "main", "Bash", command=command)
    # --- inline programs: every interpreter has its own code flags and they cluster (python3 -Bc, perl -we,
    #     node -pe); ruby and node take several -e; an argv list reaches an exec-style call in many spellings
    for name, command in (
            ('bash -e -c', "bash -e -c 'git push --force origin HEAD'"),
            ('ruby with two -e', 'ruby -e \'x = 1\' -e \'system("git push --force origin HEAD")\''),
            ('node with two -e', 'node -e \'console.log(1)\' -e \'require("child_process").execSync("git push --force origin HEAD")\''),
            ('perl -we', 'perl -we \'system("git push --force origin HEAD")\''),
            ('perl -lE', 'perl -lE \'system("git push --force origin HEAD")\''),
            ('node -p', 'node -p "require(\'child_process\').execSync(\'git push --force origin HEAD\')"'),
            ('node -pe', 'node -pe "require(\'child_process\').execSync(\'git push --force origin HEAD\')"'),
            ('python3 -Bc', 'python3 -Bc "import os; os.system(\'git push --force origin HEAD\')"'),
            ('from subprocess import run', 'python3 -c "from subprocess import run; run([\'git\',\'push\',\'--force\',\'origin\',\'HEAD\'])"'),
            ('os.execvp with the program first', 'python3 -c "import os; os.execvp(\'git\', [\'git\',\'push\',\'--force\',\'origin\',\'HEAD\'])"'),
            ('node spawnSync', 'node -e "require(\'child_process\').spawnSync(\'git\', [\'push\',\'--force\',\'origin\',\'HEAD\'])"'),
            ('node execFileSync', 'node -e "require(\'child_process\').execFileSync(\'git\', [\'push\',\'--force\',\'origin\',\'HEAD\'])"')):
        expect_deny(repo, "inline program: " + name, "main", "Bash", command=command)
    for name, command in (
            ('argv after python -c', "python3 -c 'import sys; print(sys.argv[1:])' -e 'git rebase'"),
            ('python -W with a value before -c', 'python3 -Werror::DeprecationWarning -c "print(1)"'),
            ('ruby -rjson -e', "ruby -rjson -e 'puts 1'"),
            ('perl -i.bak -pe on a build file', "perl -i.bak -pe 's/a/b/' build/x.txt"),
            ('perl -ne print', "perl -ne 'print if /push/' notes.txt"),
            ('a list that is only printed (node)', 'node -e "console.log([\'git\',\'push\',\'--force\'])"'),
            ('a list that is only printed (python)', 'python3 -c "print([\'git\',\'push\',\'--force\'])"'),
            ('subprocess git status', 'python3 -c "import subprocess; subprocess.run([\'git\',\'status\'])"'),
            ('a case statement', 'case "$x" in a) echo one ;; b) echo two ;; esac'),
            ('a backtick assignment before a push', 'x=`git rev-parse HEAD`; git push -u origin HEAD'),
            ('a case inside a loop', 'for f in a b; do case "$f" in a) echo "$f" ;; esac; done')):
        expect_allow(repo, "inline program control: " + name, "main", "Bash", command=command)
    # --- reading the command line: the guard judges what the shell runs. A comment and a heredoc body are not
    #     commands (and an apostrophe in them opens no quote), a line ending in a backslash continues, a
    #     heredoc or here-string fed to a shell or an interpreter is its program, `<<` in a string or in
    #     arithmetic is no heredoc, and the `)` of a case pattern ends no subshell. The script of sed, the
    #     program of awk and the tests of find are not paths, and find writes when the command it runs does;
    #     an exec-style call may take the program and its arguments as separate strings
    for agent, name, command in (
            ('main', 'a comment with an apostrophe before a force push',
             "# don't forget\ngit push --force origin HEAD"),
            ('main', 'a trailing comment with an apostrophe before an amend',
             "ls # it's here\ngit commit --amend -m x; echo 'ok'"),
            ('main', 'a comment with an apostrophe before rm of the kit',
             "# don't forget\nrm -rf gauntlet; echo 'done'"),
            ('main', 'a comment with an apostrophe before a redirect into the kit',
             "# it's\necho x > gauntlet/config/gauntlet.properties; echo 'ok'"),
            ('main', 'a heredoc body with an apostrophe before a force push',
             "cat > notes.txt <<EOF\ndon't\nEOF\ngit push --force origin HEAD"),
            ('main', 'a heredoc is the program of bash',
             "bash <<'EOF'\nrm -rf gauntlet\nEOF"),
            ('main', 'a heredoc is the program of bash (human-only)',
             'bash <<EOF\ngit push --force origin HEAD\nEOF'),
            ('main', 'a heredoc is the program of sh -s',
             "sh -s <<'EOF'\necho x > .claude/settings.json\nEOF"),
            ('main', 'a heredoc switches the hook off inside bash',
             "bash <<'EOF'\nexport GAUNTLET_GUARD=off\nEOF"),
            ('main', 'a heredoc is the program of python3 -',
             "python3 - <<'EOF'\nimport shutil; shutil.rmtree('gauntlet')\nEOF"),
            ('main', 'a heredoc is the program of bare python3',
             "python3 <<'EOF'\nopen('gauntlet/config/gauntlet.properties','w').write('x')\nEOF"),
            ('main', 'a python heredoc runs git through an argv list',
             "python3 - <<'EOF'\nimport subprocess\nsubprocess.run(['git','push','--force','origin','HEAD'])\nEOF"),
            ('main', 'a heredoc is the program of node',
             "node <<'EOF'\nrequire('child_process').execSync('git push --force origin HEAD')\nEOF"),
            ('main', 'a heredoc is the program of perl',
             'perl <<\'EOF\'\nsystem("git push --force origin HEAD");\nEOF'),
            ('main', 'a heredoc piped through cat into bash',
             "cat <<'EOF' | bash\nrm -rf gauntlet\nEOF"),
            ('main', 'the pipeline continues after the heredoc body',
             "cat <<'EOF' |\nrm -rf gauntlet\nEOF\nbash"),
            ('main', 'a substitution inside an unquoted heredoc body',
             'cat <<EOF\n$(rm -rf gauntlet)\nEOF'),
            ('main', 'a backtick substitution inside an unquoted heredoc body',
             'cat <<EOF\n`git push --force origin HEAD`\nEOF'),
            ('main', 'a quoted << is no heredoc',
             'echo "x << y"\nrm -rf gauntlet'),
            ('main', 'a commit message with << is no heredoc',
             'git commit -m "fix: 1 << shift"\ngit push --force origin HEAD'),
            ('main', 'a shift in arithmetic is no heredoc',
             'echo $((1<<2))\nrm -rf gauntlet'),
            ('main', 'a here-string is the program of bash',
             "bash <<< 'rm -rf gauntlet'"),
            ('main', 'a here-string is the program of bash (human-only)',
             "bash <<< 'git push --force origin HEAD'"),
            ('main', 'a here-string is the program of python3',
             'python3 <<< "import shutil; shutil.rmtree(\'gauntlet\')"'),
            ('main', 'python code piped into python3 (human-only)',
             'echo "import os; os.system(\'git push --force origin HEAD\')" | python3'),
            ('main', 'python code piped into python3 (the kit)',
             'echo "import shutil; shutil.rmtree(\'gauntlet\')" | python3'),
            ('main', 'node code piped into node',
             'echo "require(\'fs\').rmSync(\'gauntlet\',{recursive:true})" | node'),
            ('main', 'cp continued on the next line into the kit',
             'cp {M}src/main/java/A.java \\\n  gauntlet/config/A.java'),
            ('main', 'rm continued on the next line',
             'rm -rf build/tmp \\\n  gauntlet'),
            ('main', 'a redirect target on the next line',
             'echo x > \\\ngauntlet/config/gauntlet.properties'),
            ('main', 'sed -i continued on the next line',
             'sed -i s/a/b/ build/x.txt \\\n  .claude/settings.json'),
            ('main', 'a word continued on the next line',
             'git pu\\\nsh --force origin HEAD'),
            ('main', 'an option on a continuation line',
             'git commit -m x \\\n  --no-verify'),
            ('main', 'a subshell inside a case branch keeps its cd to itself',
             'case x in a) (cd /tmp && true) ;; esac; rm -rf gauntlet/hooks'),
            ('main', 'a case inside a function',
             'f() { case $1 in a) rm -rf gauntlet/hooks ;; esac; }; f a'),
            ('main', 'a comment hides the closing parenthesis of a substitution',
             'echo $(true # )\nrm -rf gauntlet/hooks)'),
            ('reviewer', 'a heredoc program of python3 is a program',
             "python3 - <<'EOF'\nprint(1)\nEOF"),
            ('reviewer', 'a heredoc program of bash that writes',
             "bash <<'EOF'\necho x > notes.txt\nEOF"),
            ('hardener', 'a heredoc program of bash outside the scope',
             "bash <<'EOF'\nsed -i s/a/b/ {M}src/main/java/A.java\nEOF"),
            ('hardener', 'cp continued on the next line outside the scope',
             'cp {M}src/test/java/ATest.java \\\n  {M}src/main/java/A.java'),
            ('main', 'find runs sed -i over the kit',
             "find gauntlet -type f -exec sed -i 's/a/b/' {} \\;"),
            ('main', 'find from the root runs sed -i on the thresholds',
             'find . -name gauntlet.properties -exec sed -i s/8/80/ {} +'),
            ('main', 'find runs perl -i over the kit',
             "find gauntlet -type f -exec perl -i -pe 's/a/b/' {} +"),
            ('main', 'find prints into the kit',
             'find {M}src/main/java -type f -fprint gauntlet/config/list.txt'),
            ('main', 'find copies into the kit',
             "find {M}src -name '*.orig' -exec cp {} gauntlet/config/ \\;"),
            ('hardener', 'find runs sed -i outside the scope',
             "find {M}src/main -name '*.java' -exec sed -i 's/a/b/' {} +"),
            ('hardener', 'sed -i outside the scope',
             "sed -i 's/a/b/' {M}src/main/java/A.java"),
            ('hardener', 'sed -i -f outside the scope',
             'sed -i -f fix.sed {M}src/main/java/A.java'),
            ('hardener', 'awk -i inplace outside the scope',
             "awk -i inplace '/x/ {print}' {M}src/main/java/A.java"),
            ('reviewer', 'find runs sed -i',
             "find . -name '*.java' -exec sed -i s/a/b/ {} +"),
            ('main', 'perl system with separate strings',
             'perl -e \'system("git","push","--force")\''),
            ('main', 'ruby system with separate strings',
             'ruby -e \'system("git", "push", "--force")\''),
            ('main', 'ruby exec without parentheses',
             'ruby -e \'exec "git", "push", "--force"\''),
            ('main', 'ruby Open3.capture2',
             'ruby -e \'Open3.capture2("git", "push", "--force")\''),
            ('main', 'python os.execlp names the program twice',
             'python3 -c "import os; os.execlp(\'git\', \'git\', \'push\', \'--force\')"'),
            ('main', 'python os.spawnlp',
             'python3 -c "import os; os.spawnlp(os.P_WAIT, \'git\', \'git\', \'commit\', \'--amend\')"'),
            ('main', 'a payload of bash -c on two lines',
             "bash -c 'echo ok\ngit push --force origin HEAD'"),
            # $'...' is the word bash makes of it: escapes decoded (review of 65c03da)
            ('main', 'an ANSI-C string as the payload of bash -c',
             "bash -c $'git push --force origin HEAD'"),
            ('main', 'an ANSI-C payload on two lines',
             "bash -c $'ls\\nrm -rf gauntlet'"),
            ('main', 'an ANSI-C string as the program',
             "$'git' push --force origin HEAD"),
            ('main', 'an ANSI-C program in octal escapes',
             "$'\\147it' push --force origin HEAD"),
            ('main', 'an ANSI-C option of git commit',
             "git commit $'--amend' -m x"),
            ('main', 'an ANSI-C option in hex escapes',
             "git push $'\\x2d-force' origin HEAD"),
            ('main', 'an ANSI-C global option of git',
             "git -c $'core.hooksPath=/dev/null' status"),
            ('main', 'eval of an ANSI-C string',
             "eval $'git push --force origin HEAD'"),
            ('main', 'an ANSI-C here-string into bash',
             "bash <<< $'git push --force origin HEAD'"),
            ('main', 'an ANSI-C path of rm',
             "rm -rf $'gaun\\x74let'"),
            ('main', 'an ANSI-C redirection target',
             "echo x > $'gauntlet/config/gauntlet.properties'"),
            # a redirection writes the word bash makes of its target, by every operator that writes (review of 2b5ccf7)
            ('main', 'a quoted piece of a redirection target',
             "echo x > 'gauntlet'/config/gauntlet.properties"),
            ('main', 'a double-quoted piece of a redirection target',
             'echo x > "gauntlet"/config/gauntlet.properties'),
            ('main', 'a quote inside a redirection target',
             "echo x > gaunt'let'/config/gauntlet.properties"),
            ('main', 'an empty quote inside a redirection target',
             "echo x >> gauntlet''/config/gauntlet.properties"),
            ('main', 'a backslash inside a redirection target',
             "echo x > gaunt\\let/config/gauntlet.properties"),
            ('main', 'an ANSI-C piece of a redirection target',
             "echo x > $'gauntlet'/config/gauntlet.properties"),
            ('main', 'a quoted $CLAUDE_PROJECT_DIR in a redirection target',
             'echo x > "$CLAUDE_PROJECT_DIR"/gauntlet/config/gauntlet.properties'),
            ('main', 'a glob as a redirection target',
             "echo x > gauntle?/config/gauntlet.properties"),
            ('main', 'a quoted piece of a redirection target into the features',
             "echo x > '{F}'/order.feature"),
            ('main', '>& with a file as its target',
             "echo x >& gauntlet/config/gauntlet.properties"),
            ('main', '>| into the kit',
             "echo x >| gauntlet/config/gauntlet.properties"),
            ('main', '>| into the kit in the payload of bash -c',
             "bash -c 'echo x >| gauntlet/config/gauntlet.properties'"),
            ('reviewer', '>& with a file as its target',
             "ls >& notes.txt"),
            ('reviewer', '>| over a file',
             "ls >| notes.txt"),
            ('main', '>& with a file under a known variable',
             'echo x >& "$CLAUDE_PROJECT_DIR"/gauntlet/config/gauntlet.properties'),
            # bash writes the file after >& and 1>& whatever the word holds (review of 7061885)
            ('reviewer', '>& with a file under a variable',
             'output=gauntlet/review-proof.tmp; echo text >&"$output"'),
            ('reviewer', '1>& with a file under a variable',
             'f=notes.txt; ls 1>&"$f"'),
            ('hardener', '>& with a variable path into the main code',
             'echo x >& "$OUT"/{M}src/main/java/A.java'),
            ('main', 'a backslash before >| writes the file',
             "echo x \\\\>| gauntlet/config/gauntlet.properties"),
            # an escaped > is a character: the | after it is a pipe, the & after it ends the command
            ('main', 'a pipe after an escaped >',
             "echo x \\>| rm -rf gauntlet"),
            ('main', 'a program piped into sh after an escaped >',
             "printf 'rm -rf gauntlet\\n' \\>| sh"),
            ('main', 'a command after an escaped > and &',
             "echo x \\>& rm -rf gauntlet"),
            ('main', 'a command after an escaped < and &',
             "echo x \\<& rm -rf gauntlet"),
            ('reviewer', 'a pipe after an escaped >',
             "echo x \\>| touch notes.txt"),
            # perl's own syntax inside the quotes is code, not shell text (review of 65c03da)
            ('main', 'perl backticks',
             "perl -e '`rm -rf gauntlet`'"),
            ('main', 'perl system with one string',
             'perl -e \'system("rm -rf gauntlet")\''),
            ('main', 'perl qx()',
             "perl -e 'qx(rm -rf gauntlet)'"),
            ('main', 'perl readpipe',
             'perl -e \'readpipe("rm -rf gauntlet")\''),
            ('reviewer', 'perl readpipe',
             'perl -e \'readpipe("touch notes.txt")\''),
            ('main', 'perl open for writing',
             'perl -e \'open(F, ">", "gauntlet/config/gauntlet.properties")\''),
            ('reviewer', 'perl system with one string',
             'perl -e \'system("touch notes.txt")\''),
            # a pattern is data only where perl reads one and only when it runs no code (review of 2c1cd0b)
            ('main', 'perl code block in a pattern',
             'perl -e \'"x" =~ /(?{ system("rm -rf gauntlet") })/\''),
            ('main', 'perl optimistic code block in a pattern',
             'perl -e \'"x" =~ /(*{ system("rm -rf gauntlet") })/\''),
            ('main', 'perl code interpolated into a pattern',
             'perl -ne \'print if /@{[ system("rm -rf gauntlet") ]}/\' README.md'),
            ('main', 'perl s///e runs its replacement',
             'perl -e \'$_ = "x"; s/x/system("rm -rf gauntlet")/e\''),
            ('main', 'perl code interpolated into the replacement of s///',
             'perl -pe \'s/x/@{[ system("rm -rf gauntlet") ]}/\' README.md'),
            ('main', 'perl division before the write',
             'perl -e \'$a = 1 / 2; system("rm -rf gauntlet")\''),
            ('main', 'perl division after a variable',
             'perl -e \'$a = $b / 2; system("rm -rf gauntlet"); $c = $d / 4\''),
            ('main', 'perl a / inside a string',
             'perl -e \'print "x, / "; system("rm -rf gauntlet"); print " / "\''),
            ('main', 'perl a / inside tr',
             'perl -e \'tr/a/(/; system("rm -rf gauntlet"); /z/\''),
            ('main', 'perl a / inside a comment',
             'perl -e \'print 1 # ( /\nsystem("rm -rf gauntlet"); print "/"\''),
            ('main', 'perl a / inside a heredoc',
             'perl -e \'print <<E;\n( /\nE\nsystem("rm -rf gauntlet"); print "/"\''),
            ('main', 'perl joins two -e into one string',
             'perl -e \'print "( \' -e \' /"; system("rm -rf gauntlet"); print "/"\''),
            ('main', 'perl sub named m',
             'perl -e \'sub m { system("rm -rf gauntlet") } &m\''),
            ('main', 'perl ${/} is a variable',
             'perl -e \'print ${/}; system("rm -rf gauntlet"); print "/"\''),
            ('main', 'perl %if is a hash',
             'perl -e \'%if = (); print %if / 1; system("rm -rf gauntlet"); /x/\''),
            ('main', 'perl & s is a call',
             'perl -e \'sub s {1} $x = & s/ 1; system("rm -rf gauntlet"); //\''),
            ('main', 'perl program attached to -e',
             'perl \'-esystem("rm -rf gauntlet")\''),
            ('main', 'perl -i after the program',
             "perl -pe 's/a/b/' -i gauntlet/README.md"),
            ('main', 'perl -i after the value of -I',
             "perl -I lib -i -pe 's/a/b/' gauntlet/README.md"),
            ('reviewer', 'perl filter that also writes',
             'perl -ne \'print if /`/; system("touch notes.txt")\' README.md'),
            ('reviewer', 'perl -pi with a backtick in the pattern',
             "perl -pi -e 's/`//g' README.md"),
            ('reviewer', 'perl code after -M',
             "perl '-MPOSIX;link(q{README.md},q{x})' /dev/null")):
        expect_deny(repo, "reading: " + name, agent, "Bash", command=command.replace("{M}", M).replace("{F}", FEATURES))
    for agent, name, command in (
            ('main', 'a comment with an apostrophe before ls',
             "# don't forget\nls -la"),
            ('main', 'a trailing comment with an apostrophe before git status',
             "echo hi # don't\ngit status"),
            ('main', 'a comment that only mentions the switch and the kit',
             "# GAUNTLET_GUARD=off and rm -rf gauntlet are the human's\nls"),
            ('main', 'a # inside a word, a string or a parameter',
             'echo a#b "c # d" $# ${#HOME}; ls'),
            ('main', 'a heredoc that is data',
             'cat > notes.txt <<EOF\nrm -rf gauntlet is forbidden, and so is\ngit push --force\nEOF'),
            ('main', 'a heredoc with quotes, # and << in it',
             'cat <<EOF > {M}src/main/java/B.java\nclass B { String s = "it\'s # 1 << 2"; }\nEOF\nls'),
            ('main', 'a commit message heredoc that names a human-only command',
             'git commit -m "$(cat <<\'EOF\'\ngit push --force is forbidden\nEOF\n)"'),
            ('main', 'a commit message heredoc with an apostrophe and a trailer',
             'git commit -m "$(cat <<\'EOF\'\nDon\'t stop\n\nCo-Authored-By: X <x@example.com>\nEOF\n)"'),
            ('main', 'a commit message heredoc with a lone double quote',
             'git commit -m "$(cat <<\'EOF\'\nFix the 5" display\nEOF\n)" && git push -u origin HEAD'),
            ('main', 'a quoted heredoc keeps $( ) as text',
             "cat <<'EOF'\n$(rm -rf gauntlet) is just text here\nEOF"),
            ('main', 'an unquoted heredoc with a harmless substitution',
             'cat <<EOF\n$(date)\nEOF'),
            ('main', 'a heredoc program of python3 that touches nothing',
             "python3 - <<'EOF'\nprint('hello')\nEOF"),
            ('main', 'a heredoc program of bash that touches nothing',
             "bash <<'EOF'\nls -la\nEOF"),
            ('main', 'a heredoc read as data by a script',
             "python3 tools/report.py <<'EOF'\ngauntlet/config/gauntlet.properties\nEOF"),
            ('main', 'tee with a heredoc that names the kit',
             "tee notes.md <<'EOF'\nsee gauntlet/README.md\nEOF"),
            ('main', 'a here-string that is data',
             'wc -l <<< "rm -rf gauntlet"'),
            ('main', 'a here-string with a substitution',
             'grep -c gauntlet <<< "$(git status)"'),
            ('main', 'a shift in arithmetic before ls',
             'echo $((1<<2))\nls'),
            ('main', 'cp continued on the next line',
             'cp {M}src/main/java/A.java \\\n  {M}src/main/java/B.java'),
            ('main', 'git commit continued on the next line',
             'git commit \\\n  -m x'),
            ('main', 'gradle continued on the next line',
             './gradlew gauntletFast \\\n  --console=plain'),
            ('main', 'python code piped into python3 that touches nothing',
             'echo "print(1)" | python3'),
            ('main', 'a case with a subshell and a later command',
             'case x in a) (cd /tmp && ls) ;; *) echo other ;; esac; ls gauntlet'),
            ('main', 'the word case as an argument',
             'echo in case of fire; (cd /tmp && ls)'),
            ('reviewer', 'a heredoc program of bash that only reads',
             "bash <<'EOF'\ngit log --oneline | head -5\nEOF"),
            ('reviewer', 'a heredoc that is data',
             'cat <<EOF\nrm -rf gauntlet\nEOF'),
            ('hardener', 'a heredoc program of bash inside the scope',
             "bash <<'EOF'\nsed -i s/a/b/ {M}src/test/java/ATest.java\nEOF"),
            ('hardener', 'sed -i inside the scope: the script is no path',
             "sed -i 's/a/b/' {M}src/test/java/ATest.java"),
            ('hardener', 'sed -i -e inside the scope',
             "sed -i -e 's/a/b/' {M}src/test/java/ATest.java"),
            ('hardener', 'sed -i.bak --expression= inside the scope',
             'sed -i.bak --expression=s/a/b/ {M}src/test/java/ATest.java'),
            ('hardener', 'awk -i inplace inside the scope: the program is no path',
             "awk -i inplace '/x/ {print}' {M}src/test/java/ATest.java"),
            ('hardener', 'find runs sed -i inside the scope',
             "find {M}src/test -name '*.java' -exec sed -i 's/a/b/' {} +"),
            ('specifier', 'sed -i on a draft feature',
             "sed -i 's/a/b/' {F}/order.feature"),
            ('reviewer', 'find runs grep',
             "find . -name '*.java' -exec grep -l TODO {} +"),
            ('main', 'find runs grep over the kit',
             "find gauntlet -name '*.py' -exec grep -n guard {} +"),
            ('main', 'find runs sed -i over the main code',
             "find {M}src/main/java -name '*.java' -exec sed -i s/a/b/ {} +"),
            ('main', 'perl system with separate strings that only reads',
             'perl -e \'system("git","status")\''),
            ('main', 'ruby system that prints the words',
             'ruby -e \'system("echo", "git push --force")\''),
            ('main', 'python print of the words',
             'python3 -c "print(\'git\', \'push\', \'--force\')"'),
            ('main', 'an ANSI-C string with an escaped quote',
             "echo $'it\\'s fine'; ls"),
            ('main', 'a commit message in an ANSI-C string that names a flag',
             "git commit -m $'fix: the user\\'s --amend flag'"),
            ('main', 'an ANSI-C format of git log',
             "git log --format=$'%h\\t%s' -3"),
            ('main', 'a quoted piece of a redirection target outside the kit',
             "echo ok > 'notes'/today.txt"),
            ('main', 'a quoted $CLAUDE_PROJECT_DIR in a redirection into build/',
             'echo ok > "$CLAUDE_PROJECT_DIR"/build/out.txt'),
            ('main', '>| over a note',
             "echo ok >| notes.txt"),
            ('reviewer', '>& onto a file descriptor',
             "ls -la >&2"),
            ('reviewer', 'a pipe after an escaped >',
             "ls \\>| head"),
            ('reviewer', 'stderr restored from a saved descriptor (review of efa4c50)',
             'exec {saved_stderr}>&2; ls 2>/dev/null; exec 2>&"$saved_stderr"'),
            # after a descriptor other than 1 bash takes no file name: a word that is no number is an error there
            ('reviewer', '2>& with a variable writes no file',
             'f=notes.txt; ls 2>&"$f"'),
            ('reviewer', 'stderr onto stdout and stdout onto stderr',
             "git status 2>&1 1>&2 | head"),
            ('reviewer', 'perl -ne with readpipe in the pattern',
             "perl -ne 'print if /readpipe/' README.md"),
            ('main', 'perl that prints',
             'perl -e \'print "hello"\''),
            ('main', 'perl -ne that reads the kit',
             "perl -ne 'print if /guard/' gauntlet/README.md"),
            ('reviewer', 'perl -ne that reads',
             "perl -ne 'print if /x/' notes.txt"),
            # a backtick or a write word inside a match pattern is data (review of 2c1cd0b)
            ('reviewer', 'perl -ne with a backtick in the pattern',
             "perl -ne 'print if /`/' README.md"),
            ('reviewer', 'perl -nle with a backtick in the pattern over the kit',
             "perl -nle 'print if /`/' gauntlet/README.md"),
            ('reviewer', 'perl -n -e with a backtick in m{}',
             "perl -n -e 'print if m{`}' README.md"),
            ('reviewer', 'perl -lne that prints inline code',
             "perl -lne 'print $1 if /`([^`]+)`/' README.md"),
            ('reviewer', 'perl -pe that strips backticks',
             "perl -pe 's/`//g' README.md"),
            ('reviewer', 'perl -ne with write words in the pattern',
             "perl -ne 'print if /system|open|unlink/ && !/`/' README.md"),
            ('reviewer', 'perl -Mstrict -ne: the i of strict is no -i',
             "perl -Mstrict -ne 'print if /`/' README.md"),
            ('reviewer', 'perl with a program attached to -ne',
             "perl '-neprint if /`/' README.md"),
            ('main', 'perl -ne with a backtick in the pattern over the kit',
             "perl -ne 'print if /`/' gauntlet/README.md"),
            ('main', 'perl -ne with a write word in the pattern over the kit',
             "perl -ne 'print if /open/' gauntlet/README.md"),
            ('hardener', 'perl -ne with a backtick in the pattern over the main code',
             "perl -ne 'print if /`/' {M}src/main/java/com/example/service/domain/Order.java")):
        expect_allow(repo, "reading control: " + name, agent, "Bash",
                     command=command.replace("{M}", M).replace("{F}", FEATURES))
    # --- what the command does: where it runs after a cd (also `cd "$(git rev-parse --show-toplevel)"`, `cd -`,
    #     popd and a target the hook cannot know), a redirection is no argument, `gh api` is judged by its
    #     endpoint and method, a text match looks at the statement that writes, the sources of cp are read
    for agent, name, command in (
            ('main', 'cd to the git toplevel, then append to the thresholds',
             'cd "$(git rev-parse --show-toplevel)" && echo "crap.threshold=30" >> gauntlet/config/gauntlet.properties'),
            ('main', 'cd to an unquoted substitution of the toplevel',
             'cd $(git rev-parse --show-toplevel) && rm -rf gauntlet/hooks'),
            ('main', 'cd to $CLAUDE_PROJECT_DIR, then overwrite the settings',
             'cd "$CLAUDE_PROJECT_DIR" && echo \'{}\' > .claude/settings.json'),
            ('main', 'cd - returns to the project',
             'cd {M}src/main && ls; cd - && rm -rf gauntlet/config'),
            ('main', 'popd returns to the project',
             'pushd {M}src >/dev/null; popd >/dev/null; rm -rf gauntlet/hooks'),
            ('main', 'cd with an option before the directory',
             'cd -P . && rm -rf gauntlet/hooks'),
            ('main', 'cd to a directory the hook cannot know',
             'cd "$SOMEWHERE" && rm -rf gauntlet/hooks'),
            ('reviewer', 'cd - and a write into the sources',
             'cd {M}src && ls; cd - >/dev/null; echo "// reviewed" >> {M}src/main/java/A.java'),
            ('hardener', 'cd - and sed outside the scope',
             "cd {M}src/test && ls; cd - >/dev/null; sed -i 's/class A/final class A/' {M}src/main/java/A.java"),
            ('main', 'a shell that reads a pipe, with a redirection',
             "echo 'git push --force' | bash 2>&1"),
            ('main', 'a shell that reads a pipe, stderr dropped',
             "echo 'rm -rf gauntlet/hooks' | sh 2>/dev/null"),
            ('main', 'amend with a redirection',
             'git commit --amend -m x 2>&1 | tail -1'),
            ('main', 'two subshells that look like arithmetic',
             '((cd gauntlet; rm -rf hooks); ls)'),
            ('main', 'gh api: merge by PUT',
             'gh api -X PUT repos/o/r/pulls/12/merge'),
            ('main', 'gh api: merge with a field',
             'gh api repos/o/r/pulls/12/merge -f merge_method=squash'),
            ('main', 'gh api: merge mutation',
             'gh api graphql -f query=\'mutation { mergePullRequest(input:{pullRequestId:"PR_x"}) { clientMutationId } }\''),
            ('main', 'gh api: auto-merge mutation',
             'gh api graphql -f query=\'mutation { enablePullRequestAutoMerge(input:{pullRequestId:"PR_x"}) { clientMutationId } }\''),
            ('main', 'gh api: delete the branch protection',
             'gh api -X DELETE repos/o/r/branches/main/protection'),
            ('main', 'gh api: protection mutation',
             'gh api graphql -f query=\'mutation { deleteBranchProtectionRule(input:{branchProtectionRuleId:"x"}) { clientMutationId } }\''),
            ('main', 'gh api: a ruleset with a field',
             'gh api repos/o/r/rulesets/7 -X PUT -f enforcement=disabled'),
            ('main', '--no-verify of a push',
             'git push --no-verify -u origin HEAD'),
            ('main', 'cp into the kit',
             'cp /tmp/checkstyle.xml gauntlet/config/checkstyle.xml'),
            ('main', 'cp -t into the kit',
             'cp -t gauntlet/config /tmp/checkstyle.xml'),
            ('main', 'rsync into the kit',
             'rsync -a /tmp/kit/ gauntlet/'),
            ('main', 'tar extracts into the kit',
             'tar xzf /tmp/kit.tgz -C gauntlet'),
            ('main', 'git restore of a kit file',
             'git restore gauntlet/config/gauntlet.properties'),
            ('main', 'git restore --staged --worktree of a kit file',
             'git restore --staged --worktree gauntlet/config/gauntlet.properties'),
            ('main', 'xargs takes the kit from its pipeline',
             'ls -d gauntlet | xargs rm -rf'),
            ('main', 'a variable holds the kit',
             'D=gauntlet; rm -rf $D/hooks'),
            ('reviewer', 'tee into the project',
             'git diff origin/main | tee review.diff'),
            ('reviewer', 'tar extracts into the project',
             'tar xf /tmp/dist.tar'),
            ('reviewer', 'unzip extracts into the project',
             'unzip /tmp/a.zip'),
            ('main', 'tar extracts over the project root',
             'tar xzf /tmp/tool.tgz'),
            ('main', 'unzip extracts over the project root',
             'unzip -o /tmp/kit.zip'),
            ('main', 'unzip -d into the kit',
             'unzip -o /tmp/kit.zip -d gauntlet'),
            ('main', 'tar extracts a member of the kit',
             'tar xf /tmp/a.tar gauntlet/config/gauntlet.properties')):
        expect_deny(repo, "doing: " + name, agent, "Bash", command=command.replace("{M}", M).replace("{F}", FEATURES))
    for agent, name, command in (
            ('hardener', 'cd to $CLAUDE_PROJECT_DIR and work in the scope',
             'cd "$CLAUDE_PROJECT_DIR" && mkdir -p {M}src/test/java/com/example/api'),
            ('hardener', 'cd to the toplevel and run the mutation gate',
             'cd "$(git rev-parse --show-toplevel)" && ./gradlew mutationGate 2>&1 | tee {M}build/pit.log'),
            ('specifier', 'cd to the toplevel and work in the scope',
             'cd "$(git rev-parse --show-toplevel)" && mkdir -p {F}/billing'),
            ('hardener', 'a redirection is no path',
             'rm -rf {M}build/reports/pitest 2>/dev/null'),
            ('hardener', 'mkdir in the scope with a redirection',
             'mkdir -p {M}src/test/java/com/example/service/api 2>/dev/null'),
            ('reviewer', 'branch --show-current with a redirection',
             'git branch --show-current 2>/dev/null'),
            ('reviewer', 'branch -a through a pipe',
             'git branch -a 2>&1 | head -20'),
            ('reviewer', 'branch --all',
             'git branch --all'),
            ('reviewer', 'branch -a --list with a pattern',
             "git branch -a --list 'claude/*'"),
            ('reviewer', 'tag --contains with a separate value',
             'git tag --contains HEAD'),
            ('reviewer', 'stash list with a redirection',
             'git stash list 2>/dev/null'),
            ('reviewer', 'a substitution with a redirection',
             'echo "branch: $(git branch --show-current 2>/dev/null)"'),
            ('reviewer', 'listing an archive',
             'unzip -l {M}build/libs/service.jar | head -20'),
            ('reviewer', 'listing a tar',
             'tar tf /tmp/dist.tar | head'),
            ('reviewer', 'tee outside the project',
             './gradlew gauntletFast --continue 2>&1 | tee /tmp/gauntletFast.log | tail -40'),
            ('reviewer', 'mkdir outside the project',
             'mkdir -p /tmp/review && git diff origin/main > /tmp/review/change.diff'),
            ('reviewer', 'an arithmetic comparison is no redirection',
             'if (( $(git diff --name-only origin/main | wc -l) > 20 )); then echo large; fi'),
            ('reviewer', 'an arithmetic expansion in a string',
             'echo "more than one: $(( $(git rev-list --count origin/main..HEAD) > 1 ))"'),
            ('reviewer', 'a payload on several lines that only reads',
             "bash -lc '\ngit status --short\ngit branch -a | head -5\n'"),
            ('main', 'reading core.hooksPath with a redirection',
             'git config core.hooksPath 2>/dev/null || echo "(unset)"'),
            ('main', 'reading core.hooksPath in a substitution',
             'echo "hooksPath=$(git config core.hooksPath 2>/dev/null)"'),
            ('main', '--no-verify as the value of --grep',
             'git log --grep "--no-verify" --oneline'),
            ('main', 'an upstream attached to -u',
             'git branch -uorigin/claude/feature-x'),
            ('main', 'unstaging everything',
             'git restore --staged .'),
            ('main', 'unstaging with reset',
             'git reset HEAD .'),
            ('main', 'reset with a redirection',
             'git reset 2>&1 | tail -3'),
            ('main', 'gh api: reading the mergeable state',
             'gh api repos/o/r/pulls/12 --jq .mergeable_state'),
            ('main', 'gh api: a reply that mentions merge and /protection',
             'gh api repos/o/r/pulls/12/comments/345/replies -f body="fixed in abc1234; nothing changes before the merge, /protection and /rulesets are untouched"'),
            ('main', 'gh api: a thread reply that mentions a ruleset',
             'gh api graphql -f query=\'mutation { addPullRequestReviewThreadReply(input: {pullRequestReviewThreadId: "T", body: "The PMD ruleset is human-owned"}) { comment { id } } }\''),
            ('main', 'gh api: reading the protection',
             'gh api repos/o/r/branches/main/protection'),
            ('main', 'gh api: is the PR merged',
             'gh api repos/o/r/pulls/12/merge'),
            ('main', 'the log of the gauntlet task is not the kit',
             './gradlew gauntlet 2>&1 | tee /tmp/gauntlet.log; echo "exit=${PIPESTATUS[0]}"'),
            ('main', 'gauntlet.log next to the build',
             'rm -f gauntlet.log; ./gradlew gauntlet > gauntlet.log 2>&1'),
            ('main', 'the word gauntlet in an echo next to rm',
             'rm -rf {M}build; ./gradlew gauntlet || echo gauntlet failed $?'),
            ('main', 'a task list in a loop next to rm',
             'rm -rf {M}build; for t in gauntletFast gauntlet; do ./gradlew $t --continue || break; done'),
            ('main', 'git pull next to a read of the kit',
             'git pull && cat gauntlet/config/gauntlet.properties'),
            ('main', 'xargs rm next to a read of the kit',
             "ls gauntlet/config; find {M}build -name '*.tmp' | xargs -n 1 rm -f"),
            ('main', 'a grep pattern is not the program of a later shell',
             'grep -n "rm -rf gauntlet" docs/notes.md; cat scripts/run.sh | bash'),
            ('main', 'a payload on several lines',
             "bash -lc '\nset -euo pipefail\nrm -rf {M}build && ./gradlew gauntlet --continue\n'"),
            ('main', 'a commit message on several lines',
             'git commit -m "chore: cleanup\n\nrm unused fixtures under src/test/resources; keep gauntlet config as is"'),
            ('main', 'copying a config out of the kit',
             'cp gauntlet/config/checkstyle.xml /tmp/checkstyle.xml'),
            ('main', 'copying the sources out of the project',
             'cp -r {M}src /tmp/src-copy'),
            ('main', 'packing the kit into an archive outside',
             'tar czf /tmp/kit.tgz gauntlet'),
            ('main', 'packing the kit to stdout',
             'tar czf - gauntlet | wc -c'),
            ('main', 'tar extracts into the build directory',
             'tar xzf /tmp/tool.tgz -C {M}build/tmp'),
            ('main', 'unzip -d into the build directory',
             'unzip -o /tmp/kit.zip -d {M}build/unzipped'),
            ('reviewer', 'tar extracts outside the project',
             'tar xf /tmp/dist.tar -C /tmp/out'),
            ('main', 'arithmetic with parentheses and a redirect into build',
             'echo $(( (1 + 2) * 3 )) > {M}build/n.txt')):
        expect_allow(repo, "doing control: " + name, agent, "Bash",
                     command=command.replace("{M}", M).replace("{F}", FEATURES))
    # --- hardening: a global option that takes its value as the next argument does not hide the subcommand
    for name, command in (
            ("--namespace commit --amend", "git --namespace x commit --amend -m x"),
            ("--namespace push --force", "git --namespace x push --force origin HEAD"),
            ("--config-env commit --amend", "git --config-env user.name=HOME commit --amend -m x"),
            ("--attr-source rebase", "git --attr-source HEAD rebase main"),
            ("--super-prefix push --force", "git --super-prefix x/ push --force origin HEAD")):
        expect_deny(repo, "global option: " + name, "main", "Bash", command=command)
    expect_deny(repo, "global option: reviewer add behind --namespace", "reviewer", "Bash", command="git --namespace x add -A")
    for name, command in (
            ("--namespace status", "git --namespace x status"),
            ("--namespace plain commit", 'git --namespace x commit -m "feat: y"'),
            ("--attr-source log", "git --attr-source HEAD log --oneline -1"),
            ("--config-env plain commit", 'git --config-env user.name=AUTHOR commit -m "feat: y"')):
        expect_allow(repo, "global option control: " + name, "main", "Bash", command=command)
    # --- hardening: core.hooksPath for any subcommand and written to a config file; aliases and config includes
    #     (they hide any human-only command behind another name)
    for name, command in (
            ("-c core.hooksPath push", "git -c core.hooksPath=/dev/null push origin HEAD"),
            ("--config-env=core.hooksPath", "git --config-env=core.hooksPath=NOHOOKS commit -m x"),
            ("--config-env core.hooksPath", "git --config-env core.hooksPath=NOHOOKS commit -m x"),
            ("config core.hooksPath", "git config core.hooksPath /dev/null"),
            ("config --local core.hookspath", "git config --local core.hookspath /dev/null"),
            ("config --global core.hooksPath", "git config --global core.hooksPath /dev/null"),
            ("config --file core.hooksPath", "git config --file .git/config core.hooksPath /dev/null"),
            ("config --add core.hooksPath", "git config --add core.hooksPath /dev/null"),
            ("config --replace-all core.hooksPath", "git config --replace-all core.hooksPath /dev/null"),
            ("config set core.hooksPath", "git config set core.hooksPath /dev/null"),
            ("bash -c config core.hooksPath", "bash -c 'git config core.hooksPath /dev/null'"),
            ("argv config core.hooksPath",
             "python3 -c \"import subprocess; subprocess.run(['git','config','core.hooksPath','/dev/null'])\""),
            ("config alias", 'git config alias.fp "push --force"'),
            ("config --global alias", "git config --global alias.fp 'push --force'"),
            ("-c alias", "git -c alias.fp='push --force' fp origin HEAD"),
            ("config include.path", "git config include.path /tmp/x.cfg"),
            ("-c include.path", "git -c include.path=/tmp/x.cfg commit -m x"),
            ("config includeIf", "git config includeIf.gitdir:/x/.path /tmp/x.cfg"),
            ("config --rename-section to alias", "git config --rename-section scratch alias"),
            ("config --rename-section to core", "git config --rename-section scratch core")):
        expect_deny(repo, "git config: " + name, "main", "Bash", command=command)
    for name, command in (
            ("read core.hooksPath", "git config core.hooksPath"),
            ("--get core.hooksPath", "git config --get core.hooksPath"),
            ("--get-all core.hooksPath", "git config --get-all core.hooksPath"),
            ("--show-origin core.hooksPath", "git config --show-origin core.hooksPath"),
            ("--type path core.hooksPath", "git config --type path core.hooksPath"),
            ("get core.hooksPath", "git config get core.hooksPath"),
            ("--list", "git config --list"),
            ("-l", "git config -l"),
            ("--unset core.hooksPath", "git config --unset core.hooksPath"),
            ("unset core.hooksPath", "git config unset core.hooksPath"),
            ("user.name", 'git config user.name "Agent"'),
            ("--global user.email", "git config --global user.email a@b.c"),
            ("-c user.name commit", "git -c user.name=x commit -m y"),
            ("-c core.quotePath status", "git -c core.quotePath=false status"),
            ("commit message mentions the command", 'git commit -m "docs: git config core.hooksPath /dev/null is forbidden"'),
            ("read alias", "git config alias.co"),
            ("--get-regexp alias", "git config --get-regexp '^alias\\.'"),
            ("--unset alias", "git config --unset alias.fp"),
            ("--remove-section alias", "git config --remove-section alias"),
            ("--get include.path", "git config --get include.path"),
            ("--rename-section elsewhere", "git config --rename-section old new")):
        expect_allow(repo, "git config control: " + name, "main", "Bash", command=command)
    # --- hardening: the CI gate and the branch protection are switched off only by the human
    for name, command in (
            ("workflow disable", "gh workflow disable gauntlet.yml"),
            ("-R workflow disable", "gh -R o/r workflow disable gauntlet.yml"),
            ("bash -c workflow disable", "bash -c 'gh workflow disable 12345'"),
            ("api workflow disable", "gh api -X PUT repos/o/r/actions/workflows/gauntlet.yml/disable"),
            ("api delete protection", "gh api --method DELETE repos/o/r/branches/main/protection"),
            ("api -XDELETE protection part", "gh api -XDELETE repos/o/r/branches/main/protection/required_status_checks"),
            ("api protection with a field (POST)", "gh api repos/o/r/branches/main/protection -f required_status_checks=null"),
            ("api actions permissions", "gh api -X PUT repos/o/r/actions/permissions -F enabled=false"),
            ("api patch ruleset", "gh api --method=PATCH repos/o/r/rulesets/1 -f enforcement=disabled"),
            ("api graphql protection mutation",
             "gh api graphql -f query='mutation { deleteBranchProtectionRule(input: {branchProtectionRuleId: \"x\"}) "
             "{ clientMutationId } }'")):
        expect_deny(repo, "gh CI switch: " + name, "main", "Bash", command=command)
    for name, command in (
            ("workflow list", "gh workflow list"),
            ("workflow view", "gh workflow view gauntlet.yml"),
            ("workflow run", "gh workflow run gauntlet.yml"),
            ("workflow enable", "gh workflow enable gauntlet.yml"),
            ("run rerun", "gh run rerun 123 --failed"),
            ("api read protection", "gh api repos/o/r/branches/main/protection"),
            ("api -X GET rulesets", "gh api -X GET repos/o/r/rulesets"),
            ("api --method GET rulesets with a field", "gh api --method GET repos/o/r/rulesets -f per_page=5"),
            ("api read actions permissions", "gh api repos/o/r/actions/permissions"),
            ("api workflow enable", "gh api -X PUT repos/o/r/actions/workflows/gauntlet.yml/enable"),
            ("api graphql protection query",
             "gh api graphql -f query='query { repository(owner:\"o\",name:\"r\") { branchProtectionRules(first: 5) "
             "{ nodes { pattern } } } }'"),
            ("pr checks", "gh pr checks 12"),
            ("pr view", "gh pr view 12"),
            ("pr comment", 'gh pr comment 12 --body "CI is green"'),
            ("pr create", "gh pr create --fill")):
        expect_allow(repo, "gh CI switch control: " + name, "main", "Bash", command=command)
    # --- hardening: case-insensitive protected paths (macOS/Windows)
    expect_deny(repo, "edit uppercase GAUNTLET", "main", "Edit",
                file_path="GAUNTLET/config/gauntlet.properties", old_string="a", new_string="b")
    expect_deny(repo, "sed uppercase GAUNTLET", "main", "Bash",
                command="sed -i 's/8/80/' GAUNTLET/config/gauntlet.properties")
    expect_deny(repo, "write mixed-case .Claude", "main", "Write", file_path=".Claude/settings.json", content="{}")
    # --- specification: before any spec commit (draft)
    check("not frozen initially", git(repo, "rev-list", "--count", "origin/main..HEAD", "--", FEATURES) == "0")
    expect_allow(repo, "specifier writes draft", "specifier", "Write", file_path=feature, content="# language: ru\n")
    expect_deny(repo, "main agent writes feature", "main", "Write", file_path=feature, content="x")
    expect_deny(repo, "specifier writes code", "specifier", "Write", file_path=M + "src/main/java/A.java", content="x")
    expect_deny(repo, "specifier edits gauntlet", "specifier", "Edit", file_path="gauntlet/CLAUDE.md", old_string="a", new_string="b")
    expect_allow(repo, "specifier bash on features", "specifier", "Bash", command=f"cp /tmp/x.feature {feature}")
    expect_deny(repo, "main bash on features", "main", "Bash", command=f"sed -i 's/10/20/' {feature}")
    expect_deny(repo, "hardener writes feature", "hardener", "Write", file_path=feature, content="x")
    if len(MODULES) > 1:
        other = MODULES[1] + "/" + FEATURES_REL + "/mapping.feature"
        expect_allow(repo, "specifier writes the other module's draft", "specifier", "Write", file_path=other, content="x")
        expect_deny(repo, "main writes the other module's feature", "main", "Write", file_path=other, content="x")
        expect_allow(repo, "hardener writes the other module's tests", "hardener", "Write",
                     file_path=MODULES[1] + "/src/test/java/com/example/MappingTest.java", content="x")
        expect_deny(repo, "hardener writes the other module's main", "hardener", "Write",
                    file_path=MODULES[1] + "/src/main/java/com/example/Mapper.java", content="x")
    # --- specification: after the spec commit (approved -> frozen)
    write(repo, feature, "# language: ru\nФункция: Заказ\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", "spec: order flow")
    check("frozen after spec commit", git(repo, "rev-list", "--count", "origin/main..HEAD", "--", FEATURES) == "1")
    r = guard(repo, "specifier", "Edit", file_path=feature, old_string="a", new_string="b")
    check("specifier blocked when frozen", r is not None and "frozen" in r, repr(r))
    expect_deny(repo, "specifier new feature file when frozen", "specifier", "Write", file_path=FEATURES + "/other.feature", content="x")
    expect_deny(repo, "main blocked when frozen", "main", "Edit", file_path=feature, old_string="a", new_string="b")
    # the same directory written as "./src/.../features/": Gradle reads it as one, so must the freeze
    props = os.path.join(repo, "gauntlet/config/gauntlet.properties")
    with open(props) as f:
        original = f.read()
    for spelling in ("./" + FEATURES_REL, FEATURES_REL + "/"):
        with open(props, "w") as f:
            f.write(original.replace("acceptance.features=" + FEATURES_REL, "acceptance.features=" + spelling))
        expect_deny(repo, f"frozen with acceptance.features={spelling}", "main", "Bash", command=f"echo x >> {feature}")
    with open(props, "w") as f:
        f.write(original)
    r = guard(repo, "main", "Bash", command=f"git rm {feature}")
    check("git rm feature when frozen", r is not None and "frozen" in r, repr(r))
    expect_deny(repo, "specifier bash on frozen features", "specifier", "Bash", command=f"cp /tmp/x.feature {feature}")
    if len(MODULES) > 1:
        other = MODULES[1] + "/" + FEATURES_REL + "/mapping.feature"
        r = guard(repo, "specifier", "Write", file_path=other, content="x")
        check("other module's spec frozen by core's spec commit", r is not None and "frozen" in r, repr(r))
        expect_deny(repo, "rm other module's acceptance dir when frozen", "main", "Bash",
                    command=f"rm -rf {MODULES[1]}/src/acceptanceTest")
    expect_deny(repo, "rm -rf src when frozen", "main", "Bash", command=f"rm -rf {M}src/acceptanceTest/resources")
    expect_deny(repo, "git rm -r src/acceptanceTest when frozen", "main", "Bash", command=f"git rm -r {M}src/acceptanceTest")
    expect_deny(repo, "git checkout base -- src when frozen", "main", "Bash", command=f"git checkout origin/main -- {M}src")
    expect_deny(repo, "git checkout base -- src behind a global option when frozen", "main", "Bash",
                command=f"git --namespace x checkout origin/main -- {M}src")
    expect_deny(repo, "cd then cp into features when frozen", "specifier", "Bash",
                command=f"cd {M}src/acceptanceTest/resources && cp /tmp/x.feature features/order.feature")
    head = git(repo, "rev-parse", "HEAD")
    git(repo, "checkout", "-q", "--detach", "origin/main")
    expect_deny(repo, "detached HEAD at base is frozen", "specifier", "Write", file_path=FEATURES + "/other.feature", content="x")
    git(repo, "checkout", "-q", "claude/feature-x")
    check("back on the branch", git(repo, "rev-parse", "HEAD") == head)
    expect_allow(repo, "rm test dir when frozen", "main", "Bash", command=f"rm -rf {M}src/test/java/com/example/service/domain/Old.java")
    expect_allow(repo, "code edits still fine when frozen", "main", "Edit",
                 file_path=M + "src/main/java/com/example/service/domain/Order.java", old_string="a", new_string="b")
    # --- roles
    expect_allow(repo, "hardener writes unit test", "hardener", "Write", file_path=M + "src/test/java/com/example/OrderTest.java", content="x")
    expect_allow(repo, "hardener writes step defs", "hardener", "Edit", file_path=M + "src/acceptanceTest/java/com/example/Steps.java",
                 old_string="a", new_string="b")
    expect_deny(repo, "hardener writes main", "hardener", "Edit", file_path=M + "src/main/java/com/example/service/domain/Order.java",
                old_string="a", new_string="b")
    expect_deny(repo, "hardener bash on main", "hardener", "Bash", command=f"sed -i 's/a/b/' {M}src/main/java/com/example/service/domain/Order.java")
    expect_allow(repo, "hardener runs pit", "hardener", "Bash", command="./gradlew mutationGate --continue")
    expect_deny(repo, "hardener sed on build script", "hardener", "Bash", command=f"sed -i 's/x/y/' {M}build.gradle.kts")
    expect_deny(repo, "hardener cp into resources", "hardener", "Bash", command=f"cp /tmp/a {M}src/acceptanceTest/resources/x.properties")
    expect_allow(repo, "hardener cp within tests", "hardener", "Bash",
                 command=f"cp {M}src/test/java/com/example/service/domain/OrderTest.java {M}src/test/java/com/example/service/domain/OrderBoundaryTest.java")
    expect_allow(repo, "hardener mkdir tests", "hardener", "Bash", command=f"mkdir -p {M}src/test/java/com/example/service/api")
    expect_deny(repo, "reviewer writes", "reviewer", "Write", file_path=M + "src/main/java/A.java", content="x")
    expect_deny(repo, "reviewer git add", "reviewer", "Bash", command="git add -A")
    expect_deny(repo, "reviewer git stash", "reviewer", "Bash", command="git stash")
    expect_deny(repo, "reviewer redirect", "reviewer", "Bash", command="echo x > notes.txt")
    expect_allow(repo, "reviewer redirect outside project", "reviewer", "Bash", command="echo x > /tmp/notes.txt")
    expect_allow(repo, "reviewer reads diff", "reviewer", "Bash", command=f"git diff $(git merge-base origin/main HEAD) -- {M}src")
    expect_allow(repo, "reviewer runs gauntletFast", "reviewer", "Bash", command="./gradlew gauntletFast --continue")
    expect_allow(repo, "reviewer branch --show-current", "reviewer", "Bash", command="git branch --show-current")
    expect_allow(repo, "reviewer branch -a", "reviewer", "Bash", command="git branch -a")
    expect_allow(repo, "reviewer stash list", "reviewer", "Bash", command="git stash list")
    expect_allow(repo, "reviewer tag -l", "reviewer", "Bash", command="git tag -l 'v*'")
    expect_deny(repo, "reviewer creates branch", "reviewer", "Bash", command="git branch scratch")
    expect_deny(repo, "reviewer tags", "reviewer", "Bash", command="git tag v9")
    expect_deny(repo, "reviewer mkdir", "reviewer", "Bash", command="mkdir notes")
    # --- after the PR is merged (base holds the same feature files) the branch is not frozen any more
    git(repo, "push", "-q", "origin", "HEAD:refs/heads/merged-main")
    props = os.path.join(repo, "gauntlet/config/gauntlet.properties")
    with open(props) as f:
        original = f.read()
    with open(props, "w") as f:
        f.write(original.replace("baseRef=origin/main", "baseRef=origin/merged-main"))
    expect_allow(repo, "merged spec -> specifier may edit again", "specifier", "Edit", file_path=feature, old_string="a", new_string="b")
    with open(props, "w") as f:
        f.write(original)
    # --- spec.approval=pr: no freeze
    props = os.path.join(repo, "gauntlet/config/gauntlet.properties")
    with open(props) as f:
        original = f.read()
    with open(props, "w") as f:
        f.write(original.replace("spec.approval=chat", "spec.approval=pr"))
    expect_allow(repo, "pr mode: specifier edits committed feature", "specifier", "Edit", file_path=feature, old_string="a", new_string="b")
    expect_deny(repo, "pr mode: main still cannot write features", "main", "Edit", file_path=feature, old_string="a", new_string="b")
    # --- the file is read as java.util.Properties reads it (gauntlet.gradle does): `key: value` and `key value` too
    for separator in (": ", " ", " = "):
        with open(props, "w") as f:
            f.write(original.replace("spec.approval=chat", "spec.approval" + separator + "pr"))
        expect_allow(repo, f"pr mode written as spec.approval{separator}pr", "specifier", "Edit", file_path=feature,
                     old_string="a", new_string="b")
    with open(props, "w") as f:
        f.write(original.replace("spec.approval=chat", "! spec.approval=pr"))
    r = guard(repo, "specifier", "Edit", file_path=feature, old_string="a", new_string="b")
    check("a line that starts with ! is a comment", r is not None and "frozen" in r, repr(r))
    with open(props, "w") as f:
        f.write(original)
    # --- guard off (human's admin environment)
    e = {"session_id": "s1", "cwd": repo, "tool_name": "Edit",
         "tool_input": {"file_path": "gauntlet/config/gauntlet.properties", "old_string": "a", "new_string": "b"}}
    r = run_hook(repo, "guard.py", e, env={"GAUNTLET_GUARD": "off"})
    check("GAUNTLET_GUARD=off allows gauntlet edits", r.returncode == 0 and not r.stdout.strip(), r.stdout)
    # --- fail open on garbage
    r = subprocess.run([sys.executable, os.path.join(repo, "gauntlet/hooks/guard.py")], input="not json", cwd=repo,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    check("fails open on malformed input", r.returncode == 0 and not r.stdout.strip())


# Revision 4.5: what the other agent tools read as their own instructions, roles, settings, hooks and skills
# (gauntlet/adapters/<vendor>.md) is human-owned like CLAUDE.md and .claude/ - in the project and in the user's home
VENDOR_FILES = ("AGENTS.md", "AGENTS.override.md", ".codex/config.toml", "GEMINI.md", ".gemini/settings.json",
                ".agents/hooks.json", ".grok/config.toml", ".kimi-code/AGENTS.md", "QWEN.md", ".qwen/settings.json",
                ".qwenignore", ".agentignore", ".aiignore", ".mcp.json")
# every way the selftest writes CLAUDE.md and .claude/ - a file that does not exist yet, as most of these do not
VENDOR_WRITES = ("echo x > {p}", "echo x >> {p}", "ls 2> {p}", "ls &> {p}", "echo x | tee {p}", "cp /tmp/x {p}",
                 "mv /tmp/x {p}", "ln -sf /tmp/x {p}", "dd if=/tmp/x of={p}", "rm -f {p}", "! rm -f {p}", "{{ rm -f {p}; }}",
                 "touch {p}", "sed -i 's/a/b/' {p}", "git checkout -- {p}", "git restore {p}",
                 "python3 -c \"open('{p}','w').write('x')\"", "cat > '{p}' <<'EOF'\nx\nEOF", "bash -lc 'echo x > {p}'",
                 "sh -s <<'EOF'\necho x > {p}\nEOF", 'rm -f "$CLAUDE_PROJECT_DIR/{p}"', 'cd "$CLAUDE_PROJECT_DIR" && cp /tmp/x {p}')
# the same files once they exist: their names are tokens then, not text
VENDOR_EDITS = ("cp /tmp/x {p}", "mv /tmp/x {p}", "rm -f {p}", "sed -i 's/a/b/' {p}", "perl -pi -e 's/a/b/' {p}",
                "echo x | tee -a {p}", "truncate -s 0 {p}", "git checkout -- {p}")
# (a file in the user's agent configuration, whose configuration the refusal names)
USER_VENDOR_FILES = (("~/.codex/config.toml", "Codex"), ("~/.codex/AGENTS.md", "Codex"),
                     ("~/.gemini/antigravity-cli/settings.json", "Antigravity"), ("~/.gemini/config/hooks.json", "Antigravity"),
                     ("~/.agents/skills/x/SKILL.md", "Kimi Code"), ("~/.agents/AGENTS.md", "Kimi Code"),
                     ("~/.grok/config.toml", "Grok Build"), ("~/.grok/hooks/gauntlet.json", "Grok Build"),
                     ("~/.kimi-code/config.toml", "Kimi Code"), ("~/.kimi-code/hooks/kimi_hooks.py", "Kimi Code"),
                     ("~/.qwen/settings.json", "Qwen Code"), ("~/.qwen/hookApprovals.json", "Qwen Code"),
                     ("~/.env", "Qwen Code"), ("/etc/codex/config.toml", "Codex"),
                     ("/etc/grok/managed_config.toml", "Grok Build"), ("/etc/qwen-code/settings.json", "Qwen Code"))
CLAUDE_HOME = "~/.claude is the user's Claude Code configuration (it could switch the hooks off). Do not touch it."


def vendor_tests(repo):
    root = os.path.dirname(repo)
    home = os.path.expanduser("~")
    # --- the vendor files in the project: every tool, every shell form, the files absent and present. They sit at the
    #     root whatever the layout: a module layout runs a sample, the root layout every form
    for path in VENDOR_FILES:
        absolute = os.path.join(repo, path)
        for tool, params in (("Edit", dict(file_path=path, old_string="a", new_string="b")),
                             ("Write", dict(file_path=absolute, content="x")),
                             ("MultiEdit", dict(file_path=absolute, edits=[{"old_string": "a", "new_string": "b"}])),
                             ("NotebookEdit", dict(notebook_path=absolute, new_source="x"))):
            expect_deny(repo, f"vendor file: {tool} {path}", "main", tool, **params)
        for form in VENDOR_WRITES if not MODULES else VENDOR_WRITES[:1] + VENDOR_WRITES[6:7] + VENDOR_WRITES[9:10]:
            expect_deny(repo, f"vendor file: {form.format(p='<f>')} with {path}", "main", "Bash",
                        command=form.format(p=path))
    if MODULES:
        checker_tests(repo)
        return
    for path in VENDOR_FILES:
        write(repo, path, "x\n")
    for path in VENDOR_FILES:
        for form in VENDOR_EDITS:
            expect_deny(repo, f"vendor file present: {form.format(p='<f>')} with {path}", "main", "Bash",
                        command=form.format(p=path))
    for path in VENDOR_FILES:
        os.remove(os.path.join(repo, path))
    for d in (".codex", ".gemini", ".agents", ".grok", ".kimi-code", ".qwen"):
        shutil.rmtree(os.path.join(repo, d))
    # any letter case (macOS, Windows); Kimi Code reads agents.md as well
    for path in ("agents.md", "AGENTS.MD", "Agents.Override.MD", ".Codex/config.toml", "gemini.md", ".GEMINI/settings.json",
                 ".Agents/hooks.json", ".GROK/config.toml", ".Kimi-Code/local.toml", "qwen.md", ".QWEN/settings.json",
                 ".QwenIgnore", ".AgentIgnore", ".AIignore", ".MCP.json"):
        expect_deny(repo, f"vendor file in another case: Edit {path}", "main", "Edit", file_path=path, old_string="a",
                    new_string="b")
        expect_deny(repo, f"vendor file in another case: redirect into {path}", "main", "Bash", command=f"echo x > {path}")
    # the directory itself, and a directory that holds it (rm -rf . takes AGENTS.md with it)
    for d in (".codex", ".gemini", ".agents", ".grok", ".kimi-code", ".qwen", ".Codex"):
        expect_deny(repo, f"vendor directory: rm -rf {d}", "main", "Bash", command=f"rm -rf {d}")
        expect_deny(repo, f"vendor directory: mv over {d}", "main", "Bash", command=f"mv /tmp/dir {d}")
        expect_deny(repo, f"vendor directory: mkdir {d}", "main", "Bash", command=f"mkdir -p {d}/agents")
    for command in ("rm -rf ./", 'rm -rf "$CLAUDE_PROJECT_DIR"', "rm -rf ${CLAUDE_PROJECT_DIR}", "rm -rf $PWD",
                    'find "$CLAUDE_PROJECT_DIR" -delete', "find . -maxdepth 1 -name AGENTS.md -delete",
                    "rm -rf .codex/..", "cd .gemini/.. && rm -rf ."):
        expect_deny(repo, f"vendor file: a directory that holds it: {command}", "main", "Bash", command=command)
    # mv and dd create a human-owned name that does not exist yet (this repository has no CLAUDE.md, .claude/ or
    # CODEOWNERS at its root): the token reader leaves such a name out, so its statement is matched as text
    for command in ("mv /tmp/x CLAUDE.md", "mv /tmp/dir .claude", "mv /tmp/x CODEOWNERS", "dd if=/tmp/x of=CLAUDE.md"):
        expect_deny(repo, f"a name that does not exist yet: {command}", "main", "Bash", command=command)
    # every role: the specifier and the hardener write only their scope, and the vendor files are in none
    for agent in ("specifier", "hardener", "reviewer"):
        expect_deny(repo, f"vendor file: {agent} writes AGENTS.md", agent, "Write", file_path="AGENTS.md", content="x")
        expect_deny(repo, f"vendor file: {agent} redirects into .codex", agent, "Bash", command="echo x > .codex/config.toml")
    # another worktree of the repository holds the same vendor files
    wt = os.path.join(root, "wt-vendor")
    git(repo, "worktree", "add", "-q", "-b", "claude/wt-vendor", wt)
    expect_deny(repo, "vendor file in another worktree: rm its AGENTS.md", "main", "Bash", command=f"rm -f {wt}/AGENTS.md")
    expect_deny(repo, "vendor file in another worktree: cd there and write .codex", "main", "Bash",
                command=f"cd {wt} && echo x > .codex/config.toml")
    expect_deny(repo, "vendor file in another worktree: Write its .mcp.json", "main", "Write",
                file_path=os.path.join(wt, ".mcp.json"), content="{}")
    git(repo, "worktree", "remove", "--force", wt)
    git(repo, "branch", "-q", "-D", "claude/wt-vendor")
    # no false positives: reading and staging them, the application's .env, names that only look alike
    for command in ("cat AGENTS.md QWEN.md GEMINI.md", "grep -rn hooks .codex .agents .grok .kimi-code .qwen",
                    "ls -la .gemini .mcp.json", "git add AGENTS.md .codex/ .mcp.json", "git diff origin/main -- AGENTS.md",
                    "git log --oneline -- .qwen .qwenignore", "cp AGENTS.md /tmp/agents.md", "tar czf /tmp/vendor.tgz .codex .agents",
                    "echo DB_URL=x > .env", "cp .env.example .env", "echo x > notes-agents.md", "echo x > AGENTS.md.txt",
                    f"mkdir -p {M}src/main/java/com/example/agents", f"echo x > {M}src/main/resources/agents.properties",
                    "mv notes.tmp notes.txt", "touch gauntlet.log"):
        expect_allow(repo, f"vendor file control: {command}", "main", "Bash", command=command)
    expect_allow(repo, "vendor file control: Write the main code of an agents package", "main", "Write",
                 file_path=M + "src/main/java/com/example/agents/Agent.java", content="x")
    # --- the user's agent configuration outside the repository, named in the refusal
    for path, whose in USER_VENDOR_FILES:
        absolute = os.path.expanduser(path)
        reason = guard(repo, "main", "Write", file_path=absolute, content="x")
        check(f"user config: Write {path} names {whose}", reason is not None and whose in reason, repr(reason))
        expect_deny(repo, f"user config: Edit {path} as written", "main", "Edit", file_path=path, old_string="a", new_string="b")
        expect_deny(repo, f"user config: redirect into {path}", "main", "Bash", command=f"echo x > {absolute}")
        expect_deny(repo, f"user config: cp over {path}", "main", "Bash", command=f"cp /tmp/x {absolute}")
        expect_deny(repo, f"user config: rm {path}", "main", "Bash", command=f"rm -rf {absolute}")
        expect_deny(repo, f"user config: reviewer writes {path}", "reviewer", "Bash", command=f"echo x >> {absolute}")
        if path.startswith("~/"):
            rel = path[2:]
            expect_deny(repo, f"user config: redirect into {path} under ~", "main", "Bash", command=f"echo x > {path}")
            expect_deny(repo, f"user config: redirect into {path} under $HOME", "main", "Bash",
                        command=f"echo 'GAUNTLET_GUARD=off' >> \"$HOME/{rel}\"")
            expect_deny(repo, f"user config: cd ~ and write {path}", "main", "Bash", command=f"cd ~ && echo x > {rel}")
            expect_deny(repo, f"user config: cd ~ and mv over {path}", "main", "Bash", command=f"cd ~ && mv /tmp/x {rel}")
            expect_deny(repo, f"user config: cd into its directory and write {path}", "main", "Bash",
                        command=f"cd {os.path.dirname(absolute)} && touch {os.path.basename(absolute)}")
            expect_deny(repo, f"user config: inline code writes {path}", "main", "Bash",
                        command=f"python3 -c \"open('{absolute}','a').write('x')\"")
            expect_deny(repo, f"user config: a variable holds {path}", "main", "Bash", command=f"F={path}; echo x | tee $F")
    for path in ("~/.Codex/config.toml", "~/.QWEN/settings.json", "~/.Kimi-Code/config.toml"):
        expect_deny(repo, f"user config in another case: {path}", "main", "Write", file_path=os.path.expanduser(path),
                    content="x")
    # ~/.claude as before, now also reached the way a command does
    for name, tool, params in (
            ("Write", "Write", dict(file_path=os.path.join(home, ".claude", "settings.json"), content="{}")),
            ("cd ~ and rm -rf .claude", "Bash", dict(command="cd ~ && rm -rf .claude")),
            ("cd ~ and redirect", "Bash", dict(command="cd ~ && echo '{}' > .claude/settings.json")),
            ("quoted $HOME", "Bash", dict(command="echo '{}' > \"$HOME/.claude/settings.json\"")),
            ("cd into ~/.claude", "Bash", dict(command="cd ~/.claude && echo '{}' > settings.json"))):
        reason = guard(repo, "main", tool, **params)
        check(f"user config: ~/.claude, {name}: the same refusal", reason == "[gauntlet guard] " + CLAUDE_HOME, repr(reason))
    # the vendor's own session data under its home: Kimi Code and Grok Build write their plan files there
    for path in ("~/.kimi-code/sessions/wd/session_t1/agents/main/plans/p.md", "~/.grok/sessions/x/plan.md",
                 "~/.grok/worktrees/service/step-1/src/main/java/A.java"):
        expect_allow(repo, f"user config control: session data {path}", "main", "Write", file_path=os.path.expanduser(path),
                     content="x")
    for command in ("cat ~/.codex/config.toml", "ls -la ~/.gemini ~/.agents", "grep -rn hooks ~/.grok ~/.kimi-code ~/.qwen",
                    "cp ~/.qwen/settings.json /tmp/qwen-settings.json", "cat ~/.env", "cp ~/.env .env.local",
                    "echo x > ~/notes.txt",
                    "mkdir -p ~/.gradle/caches", "cd ~ && cp /tmp/x .envrc-sample", "cat /etc/codex/config.toml"):
        expect_allow(repo, f"user config control: {command}", "main", "Bash", command=command)
    # where an environment variable moves the configuration, it is protected there too; never the project itself
    moved = {"CODEX_HOME": os.path.join(root, "codex-home"), "GROK_HOME": os.path.join(root, "grok-home"),
             "KIMI_CODE_HOME": os.path.join(root, "kimi-home"), "QWEN_HOME": os.path.join(root, "qwen-home"),
             "QWEN_CODE_SYSTEM_SETTINGS_PATH": os.path.join(root, "qwen-system.json")}
    for path, denied in ((os.path.join(moved["CODEX_HOME"], "config.toml"), True),
                         (os.path.join(moved["GROK_HOME"], "config.toml"), True),
                         (os.path.join(moved["GROK_HOME"], "sessions", "x", "plan.md"), False),
                         (os.path.join(moved["KIMI_CODE_HOME"], "hooks", "kimi_hooks.py"), True),
                         (os.path.join(moved["KIMI_CODE_HOME"], "sessions", "wd", "plans", "p.md"), False),
                         (os.path.join(moved["QWEN_HOME"], "settings.json"), True),
                         (moved["QWEN_CODE_SYSTEM_SETTINGS_PATH"], True),
                         (os.path.join(repo, M + "src/main/java/A.java"), False)):
        event = {"session_id": "s1", "cwd": repo, "tool_name": "Write", "tool_input": {"file_path": path, "content": "x"}}
        r = run_hook(repo, "guard.py", event, env=moved)
        check(f"user config moved by a variable: {os.path.relpath(path, root)} {'denied' if denied else 'allowed'}",
              r.returncode == 0 and bool(r.stdout.strip()) == denied, r.stdout + r.stderr)
    for variable in ("QWEN_HOME", "CODEX_HOME"):          # a variable that names a directory holding the project
        event = {"session_id": "s1", "cwd": repo, "tool_name": "Write",
                 "tool_input": {"file_path": os.path.join(repo, M + "src/main/java/A.java"), "content": "x"}}
        r = run_hook(repo, "guard.py", event, env={variable: root})
        check(f"user config: {variable} that holds the project does not take the project", r.returncode == 0
              and not r.stdout.strip(), r.stdout + r.stderr)
    checker_tests(repo)


def checker_tests(repo):
    """The checker runs the read-only cards of the core: no file and no git state changes; reading, running Gradle and
    writing outside the project are fine - the reviewer's rights (every reviewer check also runs for it: TWINS)."""
    for tool, params in (("Edit", dict(file_path=M + "src/main/java/com/example/service/domain/Order.java", old_string="a",
                                       new_string="b")),
                         ("Write", dict(file_path=M + "src/test/java/com/example/OrderTest.java", content="x")),
                         ("Write", dict(file_path=FEATURES + "/new.feature", content="x")),
                         ("Write", dict(file_path=os.path.join(repo, "notes.md"), content="x")),
                         ("MultiEdit", dict(file_path=M + "build.gradle.kts", edits=[{"old_string": "a", "new_string": "b"}])),
                         ("NotebookEdit", dict(notebook_path=os.path.join(repo, "analysis.ipynb"), new_source="x"))):
        reason = guard(repo, "checker", tool, **params)
        check(f"checker: {tool} {params.get('file_path') or params.get('notebook_path')}",
              reason is not None and "checker is read-only" in reason, repr(reason))
    for command in ("echo x > notes.txt", "echo x >> README.md", "ls >| notes.txt", "git diff | tee review.diff",
                    "mkdir notes", "touch notes.txt", "rm -f README.md", f"sed -i 's/a/b/' {M}build.gradle.kts",
                    f"cp {M}build.gradle.kts /tmp/b && cp /tmp/b {M}build.gradle.kts",
                    "python3 -c \"open('notes.txt','w').write('x')\"", "bash -lc 'echo x > notes.txt'",
                    "git add -A", "git commit -m x", "git stash", "git branch scratch", "git tag v9", "git checkout -b scratch",
                    "git switch -c scratch", "git worktree add ../wt-check HEAD", "git push -u origin HEAD",
                    "git restore --staged .", "git notes add -m x", "git merge origin/main", "git pull",
                    "git cherry-pick origin/main", "git rm --cached README.md", "git apply /tmp/x.patch"):
        expect_deny(repo, f"checker: {command}", "checker", "Bash", command=command)
    for command in ("grep -rn TODO src", "git log --oneline -5", "git show HEAD --stat", "git diff origin/main...HEAD",
                    "git status --short", "git branch -a", "git stash list", "git worktree list", "git tag -l",
                    f"git blame {M}build.gradle.kts", "cat README.md 2>/dev/null | head -5", "ls -la",
                    "find . -name '*.java' -not -path './build/*'", "./gradlew gauntletFast --continue",
                    "echo x > /tmp/checker-notes.txt", "git diff origin/main | tee /tmp/checker.diff",
                    "git log -p -- src | head -50"):
        expect_allow(repo, f"checker: {command}", "checker", "Bash", command=command)
    # README, section 9: outside the project - the separate clone a card names, /tmp - the guard lets the reviewer
    # and the checker write where it sees the path: an absolute one, ./name after cd, a redirection. A name that does
    # not exist yet has no known place, and a variable it does not expand: run in the project, that path is the
    # project's. Both sides pinned for both roles (TWINS); a git state change is refused in the clone too (rule 5)
    root = os.path.dirname(repo)
    clone = os.path.join(root, "clone-card")
    git(repo, "clone", "-q", "--shared", repo, clone)
    d = os.path.join(clone, M + "src/test/java/com/example")
    for command in (f"cp /tmp/R.java {d}/R.java", f"mkdir -p {d}/repro", "mkdir -p /tmp/checker-scratch",
                    f"cd {d} && cp /tmp/R.java ./R.java", f"cd {d} && cat > R.java <<'EOF'\nclass R {{}}\nEOF",
                    f"cd {clone} && ./gradlew test > /tmp/test.log 2>&1", f"cd {root} && touch ./notes.txt",
                    "git log -p > /tmp/log.txt"):
        expect_allow(repo, "reviewer and checker write outside the project: " + command.replace(root, "<tmp>"),
                     "reviewer", "Bash", command=command)
    for command in (f"cd {d} && touch R.java", f"cd {d} && mkdir repro", f"cd {d} && cp /tmp/R.java R.java",
                    f"cd {root} && touch notes.txt", f"C={clone}; cp /tmp/R.java $C/{M}src/test/java/com/example/",
                    f'tmp=$(mktemp -d) && git show HEAD:{M}build.gradle.kts > "$tmp/build.gradle.kts"',
                    'git log -p > "${TMPDIR:-/tmp}/log.txt"', f"git -C {clone} commit -qam wip"):
        expect_deny(repo, "reviewer and checker outside the project, no path: " + command.replace(root, "<tmp>"),
                    "reviewer", "Bash", command=command)
    reason = guard(repo, "checker", "Bash", command=f"cd {d} && touch R.java")
    check("checker outside the project, no path: the read-only refusal", reason is not None
          and "The checker is read-only" in reason, repr(reason))
    # README, section 9: a variable, a substitution or ~ in what a read-only role writes is refused wherever the command
    # runs - the same line may set it to the project (`cd /tmp && P=<project>; cp ... $P/...` passed in 4.4 and 4.5) -,
    # and so is a bare name a creating command makes in the project next to a path outside it (`mv /tmp/a notes.txt`)
    a = f"{repo}/{M}src/main/java/A.java"
    for command in (f"cd /tmp && P={repo}; cp /tmp/x $P/{M}src/main/java/A.java", f"X={a}; mv /tmp/a $X",
                    f"X={a}; tee /tmp/a $X < /dev/null", f"X={repo}/{M}src; rm -rf /tmp/a $X", f"X={a}; mv $X /tmp/",
                    f"cd /tmp && X={a}; dd if=/tmp/a of=$X",
                    f"cd /tmp && PWD={repo}; echo x > $PWD/{M}src/main/java/A.java",
                    f"HOME={repo}; echo x > ~/{M}src/main/java/A.java", "echo x > ~/notes.txt",
                    f"C={clone}; cd /tmp && cp /tmp/R.java \"$C/{M}src/test/java/com/example/R.java\"",
                    f'cd /tmp && tmp=$(mktemp -d) && git show HEAD:{M}build.gradle.kts > "$tmp/b.kts"',
                    "cd /tmp && echo x > $(mktemp)", "echo x > /tmp/review-$(date +%s).log",
                    "echo x > /tmp/review-`date +%s`.log", f"cp /tmp/x $(echo {repo})/{M}src/main/java/A.java",
                    f"cd /tmp && cp /tmp/x `echo {repo}`/{M}src/main/java/A.java", "mv /tmp/a notes.txt",
                    "touch /tmp/a notes.txt", "mkdir -p /tmp/x newdir", "git diff | tee /tmp/a.diff review.diff"):
        expect_deny(repo, "reviewer and checker, a write the guard does not expand: " + command.replace(root, "<tmp>"),
                    "reviewer", "Bash", command=command)
    for command in ("git diff $(git merge-base origin/main HEAD) > /tmp/review.diff", "cd /tmp && mv /tmp/a notes.txt",
                    "chmod +x /tmp/repro.sh", "touch -d 2024-01-01 /tmp/x", "mkdir -m 700 /tmp/x",
                    f'cp "$CLAUDE_PROJECT_DIR/{M}build.gradle.kts" /tmp/b.kts',
                    f'for f in {M}src/main/java/com/example/service/domain/*.java; do cp "$f" /tmp/; done',
                    "echo x > '/tmp/a$b.txt'"):
        expect_allow(repo, "reviewer and checker, a write as written: " + command, "reviewer", "Bash", command=command)
    reason = guard(repo, "checker", "Bash", command=f"cd /tmp && P={repo}; cp /tmp/x $P/{M}src/main/java/A.java")
    check("checker, a write the guard does not expand: the refusal names the word", reason is not None
          and "The checker is read-only" in reason and f"$P/{M}src/main/java/A.java holds a variable" in reason,
          repr(reason))
    expect_allow(repo, "main: a variable in a write outside the protected paths stays the main agent's", "main", "Bash",
                 command=f"cd /tmp && P={repo}; cp /tmp/x $P/{M}src/main/java/A.java")
    # the plumbing writes git state like the porcelain does (git's documentation): refused to both roles, in the clone
    # too; the forms that only print stay allowed
    for command in (f"git update-index --force-remove {M}build.gradle.kts", "git update-index --refresh",
                    "git update-index --cacheinfo 100644,e69de29bb2d1d6434b8b29ae775ad8c2e48c5391,notes.txt",
                    f"git update-index --assume-unchanged {M}build.gradle.kts", "git read-tree HEAD",
                    "git read-tree -m -u HEAD origin/main", "git write-tree", "git symbolic-ref HEAD refs/heads/main",
                    "git symbolic-ref -d HEAD", "git symbolic-ref -m x HEAD refs/heads/main",
                    f"git hash-object -w {M}build.gradle.kts", "git hash-object --stdin -w < /tmp/x",
                    "printf '' | git mktree", "git mktag < /tmp/tag.txt", "git commit-tree -m x HEAD^{tree}",
                    "git checkout-index -f -a", "git checkout-index -a --prefix=/tmp/export/",
                    f"git merge-file {M}build.gradle.kts /tmp/base /tmp/other", "git sparse-checkout set src",
                    "git sparse-checkout disable", "git bisect start HEAD origin/main", "git bisect reset",
                    f"git -C {clone} update-index --refresh"):
        expect_deny(repo, "reviewer and checker, plumbing that writes: " + command.replace(root, "<tmp>"), "reviewer",
                    "Bash", command=command)
    for command in ("git symbolic-ref HEAD", "git symbolic-ref --short HEAD", "git symbolic-ref -q HEAD",
                    f"git hash-object {M}build.gradle.kts", "git read-tree -n HEAD",
                    f"git merge-file -p {M}build.gradle.kts /tmp/base /tmp/other", "git sparse-checkout list",
                    "git bisect log", "mkdir -p /tmp/export && git archive HEAD | tar -x -C /tmp/export"):
        expect_allow(repo, "reviewer and checker, plumbing that only prints: " + command, "reviewer", "Bash",
                     command=command)
    shutil.rmtree(clone)


def text_match_tests(repo):
    """README, section 9: the refusals the guard gives although nothing protected is written - a protected name the
    text match finds below the root, in an unprotected path or branch name or in a path only read, and the root
    named as a directory - with the workaround the README names for each. Both sides pinned in every layout: a
    change of unseen(), mentions() or the text match that moves one of these verdicts fails here, and the README
    changes with it. The text match takes the statement when a write operand is no path the guard sees (a name that
    does not exist yet, dd's of=, a mode or a size, the subcommand of a git command that writes the work tree), and
    the whole command line outside quotes when a write holds a variable."""
    g = M + "src/main/java/com/example/gauntlet"         # a package named like the kit
    prompt = M + "src/test/resources/prompts/agents.md"  # a Spring AI prompt resource of the hardener
    for agent, command in (
            ("main", "cd docs && touch agents.md"), ("main", "git restore docs/agents.md"),
            ("main", "git rm --cached core/AGENTS.md"), ("main", "git checkout -- core/CLAUDE.md"),
            ("main", "perl -pi -e 's/a/b/' docs/agents.md"), ("hardener", f"git restore {prompt}"),
            ("main", "dd if=CLAUDE.md of=/tmp/copy.md"), ("main", "dd if=CLAUDE.md of=./copy.md"),
            ("main", "mv /tmp/gauntlet/report.txt notes.txt"), ("main", "chmod 755 /tmp/gauntlet/run.sh"),
            ("main", "chmod +x /tmp/gauntlet/run.sh"), ("main", "touch -d 2024-01-01 /tmp/gauntlet/x"),
            ("main", "truncate -s 0 /tmp/gauntlet/x.log"), ("main", f"git rm {g}/Probe.java"),
            ("main", "git merge origin/fix/gauntlet"),
            ("main", "git pull origin fix/gauntlet"), ("main", "git switch fix/gauntlet"),
            ("main", "git checkout origin/main -- :/CLAUDE.md")):   # the write the text match is there to close
        expect_deny(repo, f"text match refusal: {command}", agent, "Bash", command=command)
    for command, name in (("git switch fix/gauntlet", "gauntlet"), ("git restore docs/agents.md", "AGENTS.md"),
                          ("cp $HOME/.codex/config.toml /tmp/codex.toml", ".codex")):
        reason = guard(repo, "main", "Bash", command=command)
        check(f"text match refusal names the name it found, not a file it writes: {command}",
              reason is not None and f"This command could modify {name}," in reason, repr(reason))
    for agent, command in (
            ("main", "touch docs/agents.md"), ("main", "cd docs && touch ./agents.md"),
            ("main", "sed -i 's/a/b/' docs/agents.md"), ("main", "git update-index --force-remove core/AGENTS.md"),
            ("main", "git show :docs/agents.md > docs/agents.md"), ("hardener", f"git show :{prompt} > {prompt}"),
            ("main", "git show HEAD:core/CLAUDE.md > core/CLAUDE.md"), ("main", "cp CLAUDE.md /tmp/copy.md"),
            ("main", "cat CLAUDE.md > /tmp/copy.md"), ("main", "mv /tmp/gauntlet/report.txt ./notes.txt"),
            ("main", "cd /tmp/gauntlet && chmod 755 run.sh"), ("main", "cd /tmp/gauntlet && truncate -s 0 x.log"),
            ("main", ": > /tmp/gauntlet/x.log"), ("main", f"rm {g}/Probe.java"),
            ("main", f"rm {g}/Probe.java && git add {g}/Probe.java"),
            ("main", f"mv {g}/Probe.java {g}/Probe2.java && git add -A {g}"), ("main", "git stash push -- docs/"),
            ("main", "git fetch origin fix/gauntlet && git merge FETCH_HEAD"),
            ("main", "git switch -c fix/gauntlet-hook"), ("main", "git switch -")):
        expect_allow(repo, f"text match workaround: {command}", agent, "Bash", command=command)
    expect_allow(repo, "text match workaround: Write docs/agents.md", "main", "Write", file_path="docs/agents.md",
                 content="x")
    expect_allow(repo, "text match workaround: Edit docs/agents.md", "main", "Edit", file_path="docs/agents.md",
                 old_string="a", new_string="b")
    write(repo, "docs/agents.md", "x\n")               # once it exists, the name is a path the guard sees
    expect_allow(repo, "text match: cd docs && touch agents.md once docs/agents.md exists", "main", "Bash",
                 command="cd docs && touch agents.md")
    shutil.rmtree(os.path.join(repo, "docs"))
    # ., $PWD and "$CLAUDE_PROJECT_DIR" at the root are the whole root, gauntlet/ in it
    for command in ("cp /tmp/Makefile .", "cp /tmp/Makefile ./", "cp /tmp/Makefile ${PWD}",
                    'cp /tmp/Makefile "$CLAUDE_PROJECT_DIR"', 'cp /tmp/Makefile "$PWD"', "mv /tmp/Makefile $PWD",
                    'tar -xzf /tmp/fixtures.tgz -C "$PWD"', 'unzip -o /tmp/fixtures.zip -d "$PWD"',
                    'find "$CLAUDE_PROJECT_DIR" -name "*.orig" -delete',
                    f'find "$CLAUDE_PROJECT_DIR/{M}src" -name "*.orig" -delete'):
        expect_deny(repo, f"the root as a directory: {command}", "main", "Bash", command=command)
    for command in ("cp /tmp/Makefile ./Makefile", 'cp /tmp/Makefile "$PWD/Makefile"',
                    'find "$CLAUDE_PROJECT_DIR/build" -name "*.tmp" -delete',
                    f'find "$CLAUDE_PROJECT_DIR/{M}src/main" -name "*.orig" -delete',
                    f"tar -xzf /tmp/fixtures.tgz -C {M}src/test/resources",
                    f"cd {M}src/main/resources && cp /tmp/x.yml ."):
        expect_allow(repo, f"the root as a directory, workaround: {command}", "main", "Bash", command=command)
    # a variable in a write: the whole command line outside quotes, the user's agent configuration included
    for command in ("cp $HOME/.env .env.local", "cp $HOME/.codex/config.toml /tmp/codex.toml",
                    'ls ~/.codex 2>/dev/null; mkdir -p "$TMPDIR/x"'):
        expect_deny(repo, f"a variable in a write: {command}", "main", "Bash", command=command)
    for command in ("cp ~/.env .env.local", 'cp "$HOME/.env" .env.local', "ls ~/.codex 2>/dev/null; mkdir -p /tmp/x",
                    'mkdir -p "$TMPDIR/x"'):
        expect_allow(repo, f"a variable in a write, workaround: {command}", "main", "Bash", command=command)


def switch_tests(repo):
    """README, section 9: git switch and git checkout without a pathspec are judged by the files they change - the
    difference between HEAD and the commit they move to -, not by the text: a protected segment in a branch name no
    longer refuses (in 4.5 `git switch fix/gauntlet` to an existing branch did, 4.4 let it pass), and a switch to a
    revision whose human-owned files differ from HEAD's and from the base branch's is refused whatever its name (4.4
    and 4.5 let it pass). Both sides pinned in every layout; the repository is left as it was found."""
    root = os.path.dirname(repo)
    git(repo, "branch", "fix/gauntlet", "HEAD")
    for branch, change in (("topic/kit-change", lambda: write(repo, "gauntlet/config/extra.txt", "x\n")),
                           ("docs/gauntlet", lambda: write(repo, "docs/notes.md", "x\n")),
                           ("topic/rename", lambda: git(repo, "mv", "gauntlet/hooks/session_start.sh", "notes.sh")),
                           ("topic/agents", lambda: write(repo, "AGENTS.md", "x\n"))):
        git(repo, "checkout", "-q", "-b", branch, "claude/feature-x")
        change()
        git(repo, "add", "-A")
        git(repo, "commit", "-qm", branch)
    kit = git(repo, "rev-parse", "topic/kit-change")
    git(repo, "checkout", "-q", "claude/feature-x")                  # the branch before it, @{-1}, is topic/agents
    for agent, command in (
            ("main", "git switch fix/gauntlet"), ("main", "git checkout fix/gauntlet"),
            ("main", "git switch --detach fix/gauntlet"), ("main", "git checkout -q fix/gauntlet --"),
            ("main", "git switch -c fix/gauntlet-2 fix/gauntlet"), ("main", "git switch -c gauntlet/new"),
            ("main", "git checkout -b docs/AGENTS.md"), ("main", "git switch docs/gauntlet"),
            ("main", "git checkout -t origin/main"), ("main", "git checkout --orphan scratch"),
            ("hardener", "git switch fix/gauntlet"), ("specifier", "git checkout fix/gauntlet"),
            ("main", "git checkout -f")):
        expect_allow(repo, f"switch by the files it changes, none human-owned: {command}", agent, "Bash",
                     command=command)
    for agent, command in (
            ("main", "git switch topic/kit-change"), ("main", "git checkout topic/kit-change"),
            ("main", "git switch --detach topic/kit-change"), ("main", f"git checkout {kit}"),
            ("main", "git switch -c x topic/kit-change"), ("main", "git checkout -b x topic/kit-change"),
            ("main", "git checkout -bx topic/kit-change"),
            ("main", "git switch topic/rename"), ("main", "git switch topic/agents"), ("main", "git switch -"),
            ("main", "git checkout -"), ("main", "git switch --orphan scratch"),
            ("main", f"cd {M}src && git checkout topic/kit-change"),
            ("main", "cd missing; git checkout topic/kit-change"),
            ("main", "bash -lc 'git switch topic/kit-change'"), ("specifier", "git switch topic/kit-change"),
            ("hardener", "git checkout topic/kit-change"), ("hardener", "git switch docs/gauntlet"),
            ("specifier", "git switch docs/gauntlet")):
        expect_deny(repo, f"switch by the files it changes, a human-owned one: {command}", agent, "Bash",
                    command=command)
    reason = guard(repo, "main", "Bash", command="git switch topic/kit-change")
    check("switch: the refusal names the human-owned file", reason is not None and
          "This switch would change gauntlet/config/extra.txt, a human-owned file" in reason, repr(reason))
    # -f throws a local change away: refused for a human-owned file even when the commit holds the base's version
    with open(os.path.join(repo, "gauntlet/README.md"), "a") as f:
        f.write("a local change\n")
    for command in ("git switch -f fix/gauntlet", "git checkout -f fix/gauntlet", "git switch --discard-changes main",
                    "git checkout -f", "git checkout -qf main"):
        expect_deny(repo, f"switch that discards a local change of a human-owned file: {command}", "main", "Bash",
                    command=command)
    reason = guard(repo, "main", "Bash", command="git checkout -f fix/gauntlet")
    check("switch -f: the refusal names the local change it would discard", reason is not None
          and "would discard the local change of gauntlet/README.md" in reason, repr(reason))
    expect_allow(repo, "switch that carries a local change over: git switch fix/gauntlet", "main", "Bash",
                 command="git switch fix/gauntlet")
    git(repo, "checkout", "-q", "--", "gauntlet/README.md")
    # back to the base branch's kit is no change of the kit
    git(repo, "checkout", "-q", "topic/kit-change")
    for command in ("git switch main", "git checkout claude/feature-x", "git switch -c y origin/main"):
        expect_allow(repo, f"switch from a changed kit back to the base branch's: {command}", "main", "Bash",
                     command=command)
    expect_deny(repo, "switch from a changed kit to another one: git switch topic/agents", "main", "Bash",
                command="git switch topic/agents")
    git(repo, "checkout", "-q", "claude/feature-x")
    # a branch that is only on the remote: git switch takes it from there, and so does the guard
    git(repo, "push", "-q", "origin", "fix/gauntlet:refs/heads/fix/remote-gauntlet",
        "topic/kit-change:refs/heads/remote/kit-change")
    git(repo, "fetch", "-q", "origin")
    expect_allow(repo, "switch to a branch only on the remote: git switch fix/remote-gauntlet", "main", "Bash",
                 command="git switch fix/remote-gauntlet")
    expect_deny(repo, "switch to a branch only on the remote: git switch remote/kit-change", "main", "Bash",
                command="git switch remote/kit-change")
    expect_deny(repo, "switch to a branch only on the remote: git checkout -t origin/remote/kit-change", "main", "Bash",
                command="git checkout -t origin/remote/kit-change")
    expect_allow(repo, "switch to a branch only on the remote: --no-guess takes none (git refuses it)", "main", "Bash",
                 command="git switch --no-guess remote/kit-change")
    git(repo, "push", "-q", "origin", "--delete", "fix/remote-gauntlet", "remote/kit-change")
    git(repo, "fetch", "-q", "--prune", "origin")
    # what the guard cannot resolve is judged by its text, as before: global options other than -C, a variable, git
    # moved to another repository (GIT_DIR), a checkout of paths (workaround: git show REV:path > path) and an option
    # it does not read (--det, an abbreviation of --detach)
    for command in ("git -c advice.detachedHead=false checkout fix/gauntlet", "B=fix/gauntlet; git switch $B",
                    "GIT_DIR=.git git switch fix/gauntlet", "git checkout fix/gauntlet -- docs/notes.md",
                    "git checkout --det fix/gauntlet"):
        expect_deny(repo, f"switch the guard does not resolve, judged by its text: {command}", "main", "Bash",
                    command=command)
    # a copy outside the project is no file of it (subagents.md, 1.6); another worktree of it is
    clone, wt = os.path.join(root, "clone-switch"), os.path.join(root, "wt-switch")
    git(repo, "clone", "-q", "--shared", "--no-checkout", repo, clone)
    git(repo, "worktree", "add", "-q", "--detach", wt, "HEAD")
    expect_allow(repo, "switch in a copy outside the project: git -C <clone> checkout --quiet --detach <kit change>",
                 "main", "Bash", command=f"git -C {clone} checkout --quiet --detach {kit}")
    expect_deny(repo, "switch in another worktree of the project: git -C <worktree> switch --detach topic/kit-change",
                "main", "Bash", command=f"git -C {wt} switch --detach topic/kit-change")
    git(repo, "worktree", "remove", "--force", wt)
    shutil.rmtree(clone)
    # a repository that is the user's agent configuration (CODEX_HOME moves it, vendor_tests): the switch writes it
    home = os.path.join(root, "codex-home-repo")
    os.makedirs(home)
    git(home, "init", "-q", "-b", "main")
    git(home, "config", "user.email", "t@example.com")
    git(home, "config", "user.name", "t")
    write(home, "config.toml", "a\n")
    git(home, "add", "-A")
    git(home, "commit", "-qm", "a")
    git(home, "checkout", "-q", "-b", "other")
    write(home, "config.toml", "b\n")
    git(home, "commit", "-qam", "b")
    git(home, "checkout", "-q", "main")
    event = {"session_id": "s1", "cwd": repo, "tool_name": "Bash",
             "tool_input": {"command": f"git -C {home} switch other"}}
    r = run_hook(repo, "guard.py", event, env={"CODEX_HOME": home})
    check("switch in a repository that is the user's agent configuration: refused", r.returncode == 0
          and "the user's Codex configuration" in r.stdout, r.stdout + r.stderr)
    shutil.rmtree(home)
    # frozen specification: switching to another version of a feature, or discarding a local edit of one with -f, is
    # refused; when frozen, specifier cannot switch the feature back to base either (regression check vs 4.4)
    feature = FEATURES + "/order.feature"
    git(repo, "checkout", "-q", "-b", "features/alt", "claude/feature-x")
    write(repo, feature, "# language: ru\nФункция: Подмена\n")
    git(repo, "commit", "-qam", "alt spec")
    git(repo, "checkout", "-q", "claude/feature-x")
    for agent, command in (("main", "git switch features/alt"), ("main", "git checkout --detach features/alt"),
                           ("specifier", "git switch features/alt"), ("specifier", "git switch main"),
                           ("specifier", "git checkout --detach origin/main")):
        expect_deny(repo, f"switch that changes the frozen specification: {agent} `{command}`", agent, "Bash",
                    command=command)
    with open(os.path.join(repo, feature), "a") as f:
        f.write("# local draft\n")
    for agent, command in (("main", "git checkout -f"), ("main", "git switch -f fix/gauntlet"),
                           ("specifier", "git checkout -f")):
        expect_deny(repo, f"switch -f that discards a local feature edit: {agent} `{command}`", agent, "Bash",
                    command=command)
    git(repo, "checkout", "-q", "--", feature)
    # active hooks (.claude/, gauntlet/hooks/) installed or updated on this branch cannot be wiped or rolled back by
    # switching to an older base_ref; when the human updates them on main instead, an older branch can switch to main
    fresh = os.path.join(root, "fresh-install-repo")
    os.makedirs(fresh)
    git(fresh, "init", "-q", "-b", "main")
    git(fresh, "config", "user.email", "t@example.com")
    git(fresh, "config", "user.name", "t")
    write(fresh, "App.java", "class App {}\n")
    git(fresh, "add", "-A")
    git(fresh, "commit", "-qm", "base without kit")
    git(fresh, "update-ref", "refs/remotes/origin/main", "HEAD")
    git(fresh, "branch", "before/gauntlet", "HEAD")
    git(fresh, "checkout", "-q", "-b", "install")
    for rel in ("gauntlet/config/gauntlet.properties", "gauntlet/hooks/guard.py"):
        src = os.path.join(repo, rel)
        dst = os.path.join(fresh, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
    write(fresh, ".claude/settings.json", "{}\n")
    write(fresh, "CLAUDE.md", "@gauntlet/CLAUDE.md\n")
    git(fresh, "add", "-A")
    git(fresh, "commit", "-qm", "install kit")
    for command in ("git switch main", "git checkout --detach origin/main", "git switch before/gauntlet"):
        expect_deny(fresh, f"switch that wipes active hooks installed on the branch: {command}", "main", "Bash",
                    command=command)
    git(fresh, "branch", "-f", "main", "HEAD")
    git(fresh, "update-ref", "refs/remotes/origin/main", "HEAD")
    git(fresh, "checkout", "-q", "-b", "old-task")
    git(fresh, "checkout", "-q", "main")
    with open(os.path.join(fresh, "gauntlet/hooks/guard.py"), "a") as f:
        f.write("# updated on main by human\n")
    git(fresh, "commit", "-qam", "update guard on main")
    git(fresh, "update-ref", "refs/remotes/origin/main", "HEAD")
    git(fresh, "checkout", "-q", "old-task")
    expect_allow(fresh, "switch to main when active hooks were updated on main, not on this branch", "main", "Bash",
                 command="git switch main")
    shutil.rmtree(fresh)
    git(repo, "branch", "-q", "-D", "fix/gauntlet", "topic/kit-change", "docs/gauntlet", "topic/rename", "topic/agents",
        "features/alt")


def stop_tests(repo):
    """Runs on a fresh branch state: HEAD = spec commit, clean tree."""
    def stop(session="s2", fake_exit="0", stop_hook_active=False):
        os.makedirs(os.path.join(repo, M + "build/gauntlet"), exist_ok=True)
        write(repo, M + "build/gauntlet/fake-exit", fake_exit)
        event = {"session_id": session, "cwd": repo, "hook_event_name": "Stop", "stop_hook_active": stop_hook_active}
        return run_hook(repo, "stop_gate.py", event, args=("--task", "gauntletFast", "--max-attempts", "3", "--timeout", "60"))

    def record(session="s2"):
        return run_hook(repo, "stop_gate.py", {"session_id": session, "cwd": repo, "hook_event_name": "SessionStart"},
                        args=("--record-start",))

    def log():
        try:
            with open(os.path.join(repo, M + "build/gauntlet/gauntletFast-hook.log")) as f:
                return f.read()
        except OSError:
            return ""

    git(repo, "checkout", "-q", "main")
    git(repo, "checkout", "-q", "-b", "claude/feature-y")
    record()
    r = stop()
    check("no change -> no run", r.returncode == 0 and "fake gradle" not in log(), r.stderr)
    # uncommitted spec-only turn: never runs the gate (draft waits for the human)
    write(repo, FEATURES + "/draft.feature", "# language: ru\nФункция: Черновик\n")
    r = stop()
    check("spec-only change -> no run", r.returncode == 0 and "fake gradle" not in log(), r.stderr)
    # committed spec-only turn (Step 4 freeze with no code yet): must run full gauntletFast and hold on red
    git(repo, "add", FEATURES)
    git(repo, "commit", "-q", "-m", "spec: frozen before implementation")
    r = stop(fake_exit="1")
    check("committed spec with no code yet -> runs gate and blocks on red",
          r.returncode == 2 and "fake gradle gauntletFast" in log() and "-x acceptanceTest" not in log()
          and "attempt 1/3" in r.stderr, r.stderr + log())
    os.remove(os.path.join(repo, M + "build/gauntlet/fake-exit"))
    # code change while a second spec is still a draft: run without the acceptance tasks
    write(repo, FEATURES + "/draft2.feature", "# language: ru\nФункция: Черновик 2\n")
    write(repo, M + "src/main/java/com/example/service/domain/Order.java", "package x; class Order {}\n")
    r = stop()
    check("pending spec -> acceptance excluded", r.returncode == 0 and "-x acceptanceTest -x acceptanceCheck" in log(), log())
    check("pending spec -> note to agent", "human's approval" in r.stdout, r.stdout)
    # commit the spec: acceptance tasks come back
    git(repo, "add", FEATURES)
    git(repo, "commit", "-q", "-m", "spec: draft2")
    write(repo, M + "src/main/java/com/example/service/domain/Order.java", "package x; class Order { int a; }\n")
    r = stop()
    check("committed spec -> full gauntletFast", r.returncode == 0 and "-x acceptanceTest" not in log(), log())
    # red: blocked, and re-run only when something changed
    write(repo, M + "src/main/java/com/example/service/domain/Order.java", "package x; class Order { int b; }\n")
    r = stop(fake_exit="1")
    check("red -> exit 2 with reasons", r.returncode == 2 and "CRAP FAIL" in r.stderr and "attempt 1/3" in r.stderr, r.stderr)
    os.remove(os.path.join(repo, M + "build/gauntlet/fake-exit"))   # fake gradle would now be green if it ran
    r = stop(stop_hook_active=True)
    check("same tree -> cached red, no re-run", r.returncode == 2 and "attempt 2/3" in r.stderr, r.stderr)
    r = stop(stop_hook_active=True)
    check("attempt 3 still blocks", r.returncode == 2 and "attempt 3/3" in r.stderr, r.stderr)
    r = stop(stop_hook_active=True)
    check("attempt limit -> let go with warning", r.returncode == 0 and "still RED" in r.stdout, r.stdout + r.stderr)
    # fix -> green -> cached green
    write(repo, M + "src/main/java/com/example/service/domain/Order.java", "package x; class Order { int c; }\n")
    r = stop()
    check("changed tree -> re-run, green", r.returncode == 0 and "fake gradle gauntletFast" in log(), log())
    os.remove(os.path.join(repo, M + "build/gauntlet/gauntletFast-hook.log"))
    r = stop()
    check("green cached -> no re-run", r.returncode == 0 and not os.path.exists(os.path.join(repo, M + "build/gauntlet/gauntletFast-hook.log")))
    # a single-turn spec write+commit after a green stop (e.g. spec.approval=pr) must also run the gate
    write(repo, FEATURES + "/single_turn.feature", "# language: ru\nФункция: Сразу в коммит\n")
    git(repo, "add", FEATURES)
    git(repo, "commit", "-q", "-m", "spec: single-turn")
    r = stop(fake_exit="1")
    check("single-turn committed spec after green -> runs gate and blocks on red",
          r.returncode == 2 and "-x acceptanceTest" not in log() and "attempt 1/3" in r.stderr, r.stderr + log())
    write(repo, M + "src/main/java/com/example/service/domain/Order.java", "package x; class Order { int d; }\n")
    r = stop()
    check("single-turn committed spec implemented -> passes", r.returncode == 0, r.stderr)
    # a later spec-only turn (code unchanged since the last allowed stop) never waits for Gradle
    write(repo, FEATURES + "/second.feature", "# language: ru\nФункция: Вторая\n")
    if os.path.exists(os.path.join(repo, M + "build/gauntlet/gauntletFast-hook.log")):
        os.remove(os.path.join(repo, M + "build/gauntlet/gauntletFast-hook.log"))
    r = stop(fake_exit="1")
    check("spec-only turn after code turn -> no run", r.returncode == 0
          and not os.path.exists(os.path.join(repo, M + "build/gauntlet/gauntletFast-hook.log")), r.stderr)
    # after a green stop the agent's own Gradle runs change nothing: still no re-run
    time.sleep(1.1)
    write(repo, M + "build/test-results/test/TEST-x.xml", "<testsuite/>")
    r = stop(fake_exit="1")
    check("green mark + newer reports -> still no run", r.returncode == 0 and not os.path.exists(
        os.path.join(repo, M + "build/gauntlet/gauntletFast-hook.log")), r.stderr)
    # a released RED is remembered: later turns on the same code get a reminder, not silence
    git(repo, "add", FEATURES)
    git(repo, "commit", "-q", "-m", "spec: second (for the red scenario)")
    write(repo, M + "src/main/java/com/example/service/domain/Order.java", "package x; class Order { int e; }\n")
    for _ in range(3):
        r = stop(fake_exit="1", stop_hook_active=True)
    r = stop(fake_exit="1", stop_hook_active=True)
    check("red released after 3 attempts (2nd scenario)", r.returncode == 0 and "still RED" in r.stdout, r.stdout + r.stderr)
    r = stop(fake_exit="1")
    check("same red code next turn -> reminder", r.returncode == 0 and "reminder" in r.stdout, r.stdout + r.stderr)
    # ... a spec draft waiting for the human goes first even if the specifier ran Gradle
    write(repo, FEATURES + "/third.feature", "# language: ru\nФункция: Третья\n")
    time.sleep(1.1)
    write(repo, M + "build/test-results/acceptanceTest/TEST-z.xml", "<testsuite/>")
    r = stop(fake_exit="1")
    check("draft spec after released red -> question passes with reminder", r.returncode == 0 and "reminder" in r.stdout
          and "attempt" not in r.stderr, r.stdout + r.stderr)
    git(repo, "add", FEATURES)
    git(repo, "commit", "-q", "-m", "spec: third")
    # ... the agent fixed the environment and ran Gradle itself (newer reports, no draft): re-run, now green
    time.sleep(1.1)
    write(repo, M + "build/test-results/test/TEST-fixed.xml", "<testsuite/>")
    r = stop()
    check("released red + newer reports -> re-run -> green", r.returncode == 0 and "reminder" not in r.stdout
          and "fake gradle gauntletFast" in log(), r.stdout + r.stderr + log())
    # green while the spec was a draft, then the spec gets committed with no code change: acceptance must run now
    write(repo, FEATURES + "/fourth.feature", "# language: ru\nФункция: Четвёртая\n")
    write(repo, M + "src/main/java/com/example/service/domain/Order.java", "package x; class Order { int f; }\n")
    r = stop()
    check("draft + code -> green with acceptance excluded", r.returncode == 0 and "-x acceptanceTest" in log(), log())
    git(repo, "add", FEATURES)
    git(repo, "commit", "-q", "-m", "spec: fourth")
    r = stop(fake_exit="1")
    check("pending -> approved with same code re-checks", r.returncode == 2 and "-x acceptanceTest" not in log(), r.stderr + log())
    os.remove(os.path.join(repo, M + "build/gauntlet/fake-exit"))
    # SubagentStop (hardener, from settings.json): stop_hook_active there only says whether the subagent has Stop hooks
    # of its own, so it is always false - retries within the window must still count up to the limit and let go
    write(repo, M + "src/main/java/com/example/service/domain/Order.java", "package x; class Order { int g; }\n")
    write(repo, M + "build/gauntlet/fake-exit", "1")
    sub = {"session_id": "s2", "cwd": repo, "hook_event_name": "SubagentStop", "agent_type": "hardener",
           "agent_id": "h1", "stop_hook_active": False}
    sub_args = ("--task", "mutationGate", "--max-attempts", "2", "--timeout", "60")
    r = run_hook(repo, "stop_gate.py", sub, args=sub_args)
    check("subagent red -> blocked, attempt 1/2", r.returncode == 2 and "attempt 1/2" in r.stderr, r.stderr)
    r = run_hook(repo, "stop_gate.py", sub, args=sub_args)
    check("subagent retry counts although stop_hook_active=false", r.returncode == 2 and "attempt 2/2" in r.stderr,
          r.stderr)
    r = run_hook(repo, "stop_gate.py", sub, args=sub_args)
    check("subagent attempt limit -> let go with warning", r.returncode == 0 and "still RED" in r.stdout,
          r.stdout + r.stderr)
    os.remove(os.path.join(repo, M + "build/gauntlet/fake-exit"))
    # switched off by the human
    write(repo, M + "src/main/java/com/example/service/domain/Order.java", "package x; class Order { int d; }\n")
    write(repo, M + "build/gauntlet/fake-exit", "1")
    e = {"session_id": "s2", "cwd": repo, "hook_event_name": "Stop"}
    r = run_hook(repo, "stop_gate.py", e, env={"GAUNTLET_STOP_HOOK": "off"}, args=("--task", "gauntletFast"))
    check("GAUNTLET_STOP_HOOK=off", r.returncode == 0)


def baseline_revert_tests(repo):
    git(repo, "reset", "--hard", "-q", "main")
    git(repo, "clean", "-fdq")
    git(repo, "checkout", "-q", "-b", "claude/baseline-revert")
    source = M + "src/main/java/com/example/service/domain/Order.java"
    log = os.path.join(repo, M + "build/gauntlet/gauntletFast-hook.log")
    write(repo, source, "package x; class Order { int preexisting; }\n")
    run_hook(repo, "stop_gate.py", {"session_id": "s-revert", "cwd": repo, "hook_event_name": "SessionStart"},
             args=("--record-start",))

    def stop():
        write(repo, M + "build/gauntlet/fake-exit", "1")
        event = {"session_id": "s-revert", "cwd": repo, "hook_event_name": "Stop", "stop_hook_active": False}
        return run_hook(repo, "stop_gate.py", event,
                        args=("--task", "gauntletFast", "--max-attempts", "3", "--timeout", "60"))

    r = stop()
    check("untouched pre-existing dirty file -> no run", r.returncode == 0 and not os.path.exists(log), r.stderr)
    git(repo, "checkout", "--", source)
    r = stop()
    check("pre-existing dirty file restored to HEAD -> gate runs", r.returncode == 2 and os.path.exists(log),
          r.stdout + r.stderr)


def build_logic_tests(repo):
    """buildSrc is build logic that IntegrityCheck scans: a change only there must reach the Stop gate too."""
    git(repo, "reset", "--hard", "-q", "main")
    git(repo, "clean", "-fdq")
    git(repo, "checkout", "-q", "-b", "claude/build-logic")
    log = os.path.join(repo, M + "build/gauntlet/gauntletFast-hook.log")
    if os.path.exists(log):
        os.remove(log)
    run_hook(repo, "stop_gate.py", {"session_id": "s-buildsrc", "cwd": repo, "hook_event_name": "SessionStart"},
             args=("--record-start",))
    write(repo, M + "buildSrc/src/main/groovy/Custom.gradle", "test.enabled = false\n")
    write(repo, M + "build/gauntlet/fake-exit", "1")
    event = {"session_id": "s-buildsrc", "cwd": repo, "hook_event_name": "Stop", "stop_hook_active": False}
    r = run_hook(repo, "stop_gate.py", event, args=("--task", "gauntletFast", "--max-attempts", "3", "--timeout", "60"))
    check("buildSrc-only change -> gate runs", r.returncode == 2 and os.path.exists(log), r.stdout + r.stderr)


def multi_module_stop_tests(repo):
    """Two modules: Gradle runs only where something changed; a red module blocks."""
    core, adapter = MODULES[0], MODULES[1]

    def stop(stop_hook_active=False):
        event = {"session_id": "s3", "cwd": repo, "hook_event_name": "Stop", "stop_hook_active": stop_hook_active}
        return run_hook(repo, "stop_gate.py", event, args=("--task", "gauntletFast", "--max-attempts", "3", "--timeout", "60"))

    def log_of(module):
        path = os.path.join(repo, module, "build/gauntlet/gauntletFast-hook.log")
        try:
            with open(path) as f:
                return f.read()
        except OSError:
            return ""

    def clear_logs():
        for m in (core, adapter):
            shutil.rmtree(os.path.join(repo, m, "build"), ignore_errors=True)

    sh(repo, "git", "stash", "-u", "-q")             # leftovers of the previous scenario
    git(repo, "checkout", "-q", "main")
    git(repo, "checkout", "-q", "-b", "claude/feature-z")
    # the developer's own uncommitted work, present before the session, must not count as the agent's change
    write(repo, core + "/src/main/java/com/example/service/domain/Draft.java", "package x; class Draft {}\n")
    run_hook(repo, "stop_gate.py", {"session_id": "s3", "cwd": repo, "hook_event_name": "SessionStart"}, args=("--record-start",))
    clear_logs()
    write(repo, adapter + "/src/main/java/com/example/service/domain/Mapper.java", "package x; class Mapper {}\n")
    r = stop()
    check("adapter change -> gradle in adapter only (pre-existing dirty core file ignored)", r.returncode == 0
          and "fake gradle" in log_of(adapter) and not log_of(core), r.stderr + log_of(core))
    clear_logs()
    write(repo, core + "/src/main/java/com/example/service/domain/Order.java", "package x; class Order { int z; }\n")
    r = stop()
    check("both changed -> gradle in both", r.returncode == 0 and "fake gradle" in log_of(core) and "fake gradle" in log_of(adapter),
          r.stderr)
    write(repo, adapter + "/build/gauntlet/fake-exit", "1")
    write(repo, adapter + "/src/main/java/com/example/service/domain/Mapper.java", "package x; class Mapper { int a; }\n")
    r = stop()
    check("red adapter blocks", r.returncode == 2 and f"=== {adapter} ===" in r.stderr and "CRAP FAIL" in r.stderr, r.stderr)
    os.remove(os.path.join(repo, adapter, "build/gauntlet/fake-exit"))
    write(repo, adapter + "/src/main/java/com/example/service/domain/Mapper.java", "package x; class Mapper { int b; }\n")
    r = stop(stop_hook_active=True)
    check("fixed adapter -> green", r.returncode == 0, r.stderr)


def cache_isolation_tests(repo):
    """F13a: a red-run cache is judged per module. Core's reports being newer than ADAPTER's log must not
    invalidate the cache while each module's own reports are older than its own log; a genuinely newer report
    in one module must still force a re-run."""
    core, adapter = MODULES[0], MODULES[1]
    git(repo, "checkout", "-q", "main")
    git(repo, "checkout", "-q", "-b", "claude/cache-iso")
    run_hook(repo, "stop_gate.py", {"session_id": "s4", "cwd": repo, "hook_event_name": "SessionStart"}, args=("--record-start",))

    def stop(active=True):
        ev = {"session_id": "s4", "cwd": repo, "hook_event_name": "Stop", "stop_hook_active": active}
        return run_hook(repo, "stop_gate.py", ev, args=("--task", "gauntletFast", "--max-attempts", "5", "--timeout", "60"))

    def setm(rel, t):
        p = os.path.join(repo, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        if not os.path.exists(p):
            write(repo, rel, "<testsuite/>")
        os.utime(p, (t, t))

    write(repo, core + "/src/main/java/com/example/service/domain/Order.java", "package x; class Order { int iso; }\n")
    write(repo, adapter + "/src/main/java/com/example/service/domain/Mapper.java", "package x; class Mapper { int iso; }\n")
    for m in (core, adapter):
        write(repo, m + "/build/gauntlet/fake-exit", "1")
    r = stop(active=False)
    check("F13a setup: both modules red on the first run", r.returncode == 2, r.stderr)
    for m in (core, adapter):
        os.remove(os.path.join(repo, m, "build/gauntlet/fake-exit"))   # a re-run would now be green
    base = time.time()
    core_log = core + "/build/gauntlet/gauntletFast-hook.log"
    setm(core_log, base + 100)
    setm(adapter + "/build/gauntlet/gauntletFast-hook.log", base + 50)
    setm(core + "/build/test-results/test/TEST-c.xml", base + 90)      # newer than adapter's log, older than core's
    setm(adapter + "/build/test-results/test/TEST-a.xml", base + 40)
    r = stop(active=True)
    core_mtime = os.path.getmtime(os.path.join(repo, core_log))
    check("F13a: cross-module reports do not invalidate a fresh per-module cache (cached red, no re-run)",
          r.returncode == 2 and abs(core_mtime - (base + 100)) < 1, f"rc={r.returncode} core_log_mtime={core_mtime} want~{base + 100}")
    setm(adapter + "/build/test-results/test/TEST-a2.xml", base + 200)  # genuinely newer than adapter's own log
    r = stop(active=True)
    check("F13a control: a newer report in one module forces a re-run (now green)", r.returncode == 0, r.stderr)


def continuation_window_tests(repo):
    """Kimi P2: the SubagentStop continuation window must cover the WHOLE multi-module run budget
    (modules * timeout), not a single module's timeout, or a slow two-module mutationGate resets the counter."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("sg_win", os.path.join(repo, "gauntlet", "hooks", "stop_gate.py"))
    sg = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sg)
    f = os.path.join(repo, "build", "gauntlet", "window.attempts")
    os.makedirs(os.path.dirname(f), exist_ok=True)

    def attempt(age, timeout, modules):
        with open(f, "w") as fh:
            fh.write(f"1 {time.time() - age}")
        return sg.next_attempt(f, {"hook_event_name": "SubagentStop"}, timeout, modules)

    check("window: single module slow run continues", attempt(3500, 3300, 1) == 2)
    check("window: two modules 4000s continues (not reset)", attempt(4000, 3300, 2) == 2)
    check("window: two modules full 6600s continues", attempt(6600, 3300, 2) == 2)
    check("window: beyond total budget resets", attempt(7300, 3300, 2) == 1)
    check("window: single module beyond its budget resets", attempt(4000, 3300, 1) == 1)


def gate_scope_tests(repo):
    """V6-c: a signal-killed Gradle run reports a negative return code - run_all must call it RED, not green.
    V7-2: affected_modules maps a change to the builds it touches (own dir, module overlay, shared config/tooling
    = every module), and uncommitted_paths splits a git rename into both ends."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("sg_scope", os.path.join(repo, "gauntlet", "hooks", "stop_gate.py"))
    sg = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sg)

    orig_gradle = sg.run_gradle
    sg.run_gradle = lambda task, excluded, timeout, module=".": (-15, "log", "killed by signal")
    try:
        code, _ = sg.run_all(["."], "gauntletFast", [], 60)
        check("V6-c: signal-killed gradle (negative code) is red", code == 1, str(code))
        code2, _ = sg.run_all(["core", "adapter"], "gauntletFast", [], 60)
        check("V6-c: negative code red across modules", code2 == 1, str(code2))
    finally:
        sg.run_gradle = orig_gradle

    # a run that hits the timeout must hand its partial output to the agent (POSIX delivers it as bytes)
    slow = tempfile.mkdtemp(prefix="gauntlet-selftest-slow-")
    try:
        with open(os.path.join(slow, "gradlew"), "w") as f:
            f.write("#!/bin/sh\necho 'CRAP FAIL com.example.X#y'\nexec sleep 60\n")
        os.chmod(os.path.join(slow, "gradlew"), 0o755)
        code, _, output = sg.run_gradle("gauntletFast", [], 5, slow)    # time for the echo on a loaded machine
        check("timeout is red and keeps the partial Gradle output",
              code == 124 and "CRAP FAIL com.example.X#y" in output and "TIMEOUT" in output, f"{code} {output!r}")
    finally:
        shutil.rmtree(slow, ignore_errors=True)

    sg.MODULES = ["core", "adapter"]
    am = sg.affected_modules
    check("V7-2: own module dir touches only that module", am({"core/src/main/java/X.java"}) == ["core"], str(am({"core/src/main/java/X.java"})))
    check("V7-2: other module dir touches that module", am({"adapter/src/main/java/Y.java"}) == ["adapter"])
    check("V7-2: module overlay config touches only that module", am({"gauntlet/config/core/gauntlet.properties"}) == ["core"])
    check("V7-2: shared common config touches every module", set(am({"gauntlet/config/gauntlet.properties"})) == {"core", "adapter"})
    check("V7-2: shared hook touches every module", set(am({"gauntlet/hooks/guard.py"})) == {"core", "adapter"})
    check("V7-2: shared gradle script touches every module", set(am({"gauntlet/gauntlet.gradle"})) == {"core", "adapter"})
    check("V7-2: rename across modules touches both", set(am({"core/src/main/java/X.java", "adapter/src/main/java/X.java"})) == {"core", "adapter"})
    check("V7-2: unrelated path touches no build", am({"README.md"}) == [])
    sg.MODULES = []
    check("V7-2: no modules -> single root build", am({"src/main/java/X.java"}) == ["."])

    orig_bytes = sg.git_bytes
    try:
        sg.git_bytes = lambda *a, **k: b"R  core/src/main/java/X.java -> adapter/src/main/java/X.java\n"
        check("V7-2: rename yields both ends", sg.uncommitted_paths() == {"core/src/main/java/X.java", "adapter/src/main/java/X.java"},
              str(sg.uncommitted_paths()))
        sg.git_bytes = lambda *a, **k: b' M "core/src/main/java/My File.java"\nR  core/a.java -> adapter/a.java\n'
        check("V7-2: quoted path and rename parsed", sg.uncommitted_paths() == {"core/src/main/java/My File.java", "core/a.java", "adapter/a.java"},
              str(sg.uncommitted_paths()))
    finally:
        sg.git_bytes = orig_bytes

    # F7: nested builds (module=core,core/adapter) - the longest prefix owns the change, not the first match
    am = sg.affected_modules
    for order in (["core", "core/adapter"], ["core/adapter", "core"]):
        sg.MODULES = order
        tag = ",".join(order)
        check(f"F7: nested change runs the inner build, not the parent (order {tag})",
              am({"core/adapter/src/main/java/X.java"}) == ["core/adapter"], str(am({"core/adapter/src/main/java/X.java"})))
        check(f"F7: parent-only change runs the parent (order {tag})", am({"core/src/main/java/X.java"}) == ["core"])
        check(f"F7: nested overlay config runs the inner build (order {tag})",
              am({"gauntlet/config/core/adapter/gauntlet.properties"}) == ["core/adapter"])
    sg.MODULES = []

    # F6: a COMMITTED rename across modules yields both ends (--name-status -M), so the source build runs too
    orig_git, orig_uncommitted = sg.git, sg.uncommitted_paths
    try:
        sg.uncommitted_paths = lambda: set()

        def fake_git(*a):
            if a[:2] == ("rev-parse", "HEAD"):
                return "newhead"
            if a and a[0] == "diff":
                return "R100\tcore/src/main/java/X.java\tadapter/src/main/java/X.java\nM\tcore/build.gradle\n"
            return ""
        sg.git = fake_git
        cp = sg.changed_paths("oldhead")
        check("F6: committed rename yields both ends + the modified file",
              cp == {"core/src/main/java/X.java", "adapter/src/main/java/X.java", "core/build.gradle"}, str(cp))
        check("F6: committed rename includes the SOURCE module path", "core/src/main/java/X.java" in cp, str(cp))
    finally:
        sg.git, sg.uncommitted_paths = orig_git, orig_uncommitted

    # F13a: report dirs are per module, so one module's cache is never judged against another's reports
    check("F13a: root module report dirs", sg.module_report_dirs(".") == ["build/test-results", "build/reports"])
    check("F13a: nested module report dirs",
          sg.module_report_dirs("core/adapter") == ["core/adapter/build/test-results", "core/adapter/build/reports"])

    # F13b: a deleted tracked build.gradle stays in the git pathspec (index or HEAD), else its module is skipped
    orig_watched = sg.WATCHED
    tmp = tempfile.mkdtemp(prefix="sg-existing-")
    old_cwd = os.getcwd()
    try:
        subprocess.run(["git", "init", "-q", tmp], check=True)
        subprocess.run(["git", "-C", tmp, "config", "user.email", "t@example.com"], check=True)
        subprocess.run(["git", "-C", tmp, "config", "user.name", "t"], check=True)
        bg = os.path.join(tmp, "build.gradle")
        with open(bg, "w") as f:
            f.write("plugins { java }\n")
        subprocess.run(["git", "-C", tmp, "add", "build.gradle"], check=True)
        subprocess.run(["git", "-C", tmp, "commit", "-qm", "base"], check=True)
        os.chdir(tmp)
        sg.WATCHED = ["build.gradle", "src"]
        os.remove(bg)   # uncommitted deletion: absent on disk, still in the index
        check("F13b: uncommitted-deleted build.gradle stays in pathspec",
              "build.gradle" in sg.existing(["build.gradle", "src"]), str(sg.existing(["build.gradle", "src"])))
        subprocess.run(["git", "-C", tmp, "rm", "-q", "--cached", "build.gradle"], check=True)  # staged deletion: in HEAD, not index
        check("F13b: staged-deleted build.gradle stays via HEAD",
              "build.gradle" in sg.existing(["build.gradle"]), str(sg.existing(["build.gradle"])))
        check("F13b: unknown absent path is dropped", "src" not in sg.existing(["build.gradle", "src"]))
    finally:
        os.chdir(old_cwd)
        sg.WATCHED = orig_watched
        shutil.rmtree(tmp, ignore_errors=True)




def test_inputs_stop_tests(repo, settings, session):
    """Files the tests read outside their source sets (acceptance.inputs, test.inputs; settings = (key, document) pairs,
    one or both keys for the same build) are code for the gate; any other document is not. The settings are per module
    (gauntlet/config/<module>/gauntlet.properties) or, for a build at the root, in the common file."""
    def stop():
        event = {"session_id": session, "cwd": repo, "hook_event_name": "Stop", "stop_hook_active": False}
        return run_hook(repo, "stop_gate.py", event, args=("--task", "gauntletFast", "--max-attempts", "3", "--timeout", "60"))

    def log_of(prefix):
        try:
            with open(os.path.join(repo, prefix + "build/gauntlet/gauntletFast-hook.log")) as f:
                return f.read()
        except OSError:
            return ""

    def fresh_logs():
        for prefix in [M] + others:
            shutil.rmtree(os.path.join(repo, prefix + "build"), ignore_errors=True)

    keys = "+".join(key for key, _ in settings)
    others = [m + "/" for m in MODULES[1:]]
    sh(repo, "git", "stash", "-u", "-q")
    git(repo, "checkout", "-q", "main")
    git(repo, "checkout", "-q", "-b", "claude/feature-docs-" + session)
    lines = "".join(f"{key}={document}\n" for key, document in settings)
    if MODULES:
        write(repo, "gauntlet/config/" + MODULES[0] + "/gauntlet.properties", lines)
    else:
        with open(os.path.join(repo, "gauntlet/config/gauntlet.properties"), "a") as f:
            f.write(lines)
    for prefix in [M] + others:
        for _, document in settings:
            write(repo, prefix + document, "# Document the tests read\n")
    write(repo, M + "docs/design.md", "# Design\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", "documents and test inputs")
    fresh_logs()
    run_hook(repo, "stop_gate.py", {"session_id": session, "cwd": repo, "hook_event_name": "SessionStart"},
             args=("--record-start",))
    write(repo, M + "docs/design.md", "# Design\nchanged\n")
    r = stop()
    check(f"{keys}: another document changed -> no run", r.returncode == 0 and "fake gradle" not in log_of(M),
          r.stderr + log_of(M))
    for key, document in settings:
        fresh_logs()
        write(repo, M + document, "# Document the tests read\nchanged\n")
        r = stop()
        check(f"{keys}: {document} ({key}) changed -> gradle runs", r.returncode == 0 and "fake gradle" in log_of(M),
              r.stderr + log_of(M))
    for key, document in settings:
        fresh_logs()
        os.remove(os.path.join(repo, M + document))
        r = stop()
        check(f"{keys}: {document} ({key}) deleted -> gradle runs", r.returncode == 0 and "fake gradle" in log_of(M),
              r.stderr + log_of(M))
    for prefix in others:
        fresh_logs()
        for _, document in settings:
            write(repo, prefix + document, "# Document the tests read\nchanged\n")
        r = stop()
        check(f"{keys}: the same files in a module without the settings -> no run there",
              r.returncode == 0 and "fake gradle" not in log_of(prefix), r.stderr + log_of(prefix))


def hook_inputs(root):
    """The test inputs the Stop hook watches in the repository at root (paths from the root), computed by the hook's own
    code - the same parser and the same overlay of the common settings as the hook uses."""
    spec = importlib.util.spec_from_file_location("stop_gate_under_test", os.path.join(HOOKS, "stop_gate.py"))
    hook = importlib.util.module_from_spec(spec)
    writes_bytecode, sys.dont_write_bytecode = sys.dont_write_bytecode, True
    cwd = os.getcwd()
    try:
        spec.loader.exec_module(hook)
        os.chdir(root)
        hook.configure_module()
        return list(hook.INPUTS)
    finally:
        os.chdir(cwd)
        sys.dont_write_bytecode = writes_bytecode


def workflow_paths_tests():
    """In the repository the kit is installed in: every file the tests read outside the source sets (acceptance.inputs,
    test.inputs - exactly the list the Stop hook watches) is listed by name at the end of the CI `paths` filter, after
    the '!' patterns, so that no exclusion of documents, today's or a later one, makes CI skip a change to it."""
    root = os.path.dirname(KIT)
    workflow = os.path.join(root, ".github", "workflows", "gauntlet.yml")
    if not os.path.isfile(workflow):
        return
    with open(workflow) as f:
        block = f.read().split("paths:", 1)[-1].split("workflow_dispatch", 1)[0]
    patterns = [m.group(2) for m in re.finditer(r"^\s+-\s+(['\"])(.+?)\1", block, re.M)]
    check("CI paths: every item is a quoted pattern", len(re.findall(r"^\s+-\s+\S", block, re.M)) == len(patterns),
          block)
    negatives = [i for i, p in enumerate(patterns) if p.startswith("!")]
    tail = patterns[(negatives[-1] + 1) if negatives else 0:]
    for path in hook_inputs(root):
        check(f"CI paths list {path} after the '!' patterns", path in tail, str(patterns))


def properties_format_tests():
    """gauntlet.gradle reads the settings with java.util.Properties, the hooks only one-line key=value: every setting
    line of the kit's gauntlet.properties files has that form, so Gradle and the hooks read the same values."""
    for folder, _, files in sorted(os.walk(os.path.join(KIT, "config"))):
        if "gauntlet.properties" not in files:
            continue
        path = os.path.join(folder, "gauntlet.properties")
        with open(path, encoding="utf-8") as f:
            settings = [line.strip() for line in f if line.strip() and not line.lstrip().startswith(("#", "!"))]
        odd = [line for line in settings if not re.match(r"^[^=:\s]+=", line) or line.endswith("\\")]
        check(f"{os.path.relpath(path, KIT)}: every setting is one key=value line", not odd, str(odd))


def session_start_tests(repo):
    gradle_home = os.path.join(os.path.dirname(repo), "gradle-home")
    r = subprocess.run(["bash", os.path.join(repo, "gauntlet/hooks/session_start.sh")], cwd=repo, input="{}",
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                       env=dict(os.environ, CLAUDE_PROJECT_DIR=repo, CLAUDE_CODE_REMOTE="true",
                                GRADLE_USER_HOME=gradle_home, GAUNTLET_DOCKER="off"))
    check("session_start reports active", r.returncode == 0 and "[gauntlet] active" in r.stdout, r.stdout + r.stderr)
    check("session_start writes publicRepositories=true in cloud", os.path.isfile(os.path.join(gradle_home, "gradle.properties")), r.stdout)
    # base branch missing: simulate by pointing baseRef at a branch the remote does not have
    props = os.path.join(repo, "gauntlet/config/gauntlet.properties")
    with open(props) as f:
        original = f.read()
    with open(props, "w") as f:
        f.write(original.replace("baseRef=origin/main", "baseRef=origin/nowhere"))
    r = subprocess.run(["bash", os.path.join(repo, "gauntlet/hooks/session_start.sh")], cwd=repo, input="{}",
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                       env=dict(os.environ, CLAUDE_PROJECT_DIR=repo, CLAUDE_CODE_REMOTE="true", GRADLE_USER_HOME=gradle_home, GAUNTLET_DOCKER="off"))
    check("session_start warns about missing base", r.returncode == 0 and "WARNING" in r.stdout, r.stdout + r.stderr)
    with open(props, "w") as f:
        f.write(original)
    # shallow clone of the bare origin: the hook must fetch enough history to find the merge base
    root = os.path.dirname(repo)
    shallow = os.path.join(root, "shallow")
    subprocess.run(["git", "clone", "-q", "--depth", "1", "--branch", "claude/feature-x",
                    "file://" + os.path.join(root, "origin.git"), shallow], check=True)
    check("clone is shallow and single-branch", git(shallow, "rev-parse", "--is-shallow-repository") == "true"
          and "origin/main" not in git(shallow, "branch", "-r"))
    r = subprocess.run(["bash", os.path.join(shallow, "gauntlet/hooks/session_start.sh")], cwd=shallow, input="{}",
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                       env=dict(os.environ, CLAUDE_PROJECT_DIR=shallow, CLAUDE_CODE_REMOTE="true", GRADLE_USER_HOME=gradle_home, GAUNTLET_DOCKER="off"))
    check("session_start fetches the base in a shallow clone", "[gauntlet] active" in r.stdout, r.stdout + r.stderr)


def main():
    global M, MODULES, FEATURES
    for MODULES in ([], ["core"], ["core", "adapter"]):
        M = MODULES[0] + "/" if MODULES else ""
        FEATURES = M + FEATURES_REL
        print("layout: " + ("build at the repository root" if not MODULES else "builds in modules " + ", ".join(MODULES)))
        repo = make_repo()
        try:
            guard_tests(repo)
            vendor_tests(repo)
            text_match_tests(repo)
            switch_tests(repo)
            git(repo, "push", "-q", "origin", "claude/feature-x")
            session_start_tests(repo)
            stop_tests(repo)
            baseline_revert_tests(repo)
            build_logic_tests(repo)
            continuation_window_tests(repo)
            gate_scope_tests(repo)
            if len(MODULES) > 1:
                multi_module_stop_tests(repo)
                cache_isolation_tests(repo)
            test_inputs_stop_tests(repo, [("acceptance.inputs", "docs/operations.md")], "s5")
            test_inputs_stop_tests(repo, [("test.inputs", "docs/openapi.yaml")], "s6")
            test_inputs_stop_tests(repo, [("acceptance.inputs", "docs/operations.md"),
                                          ("test.inputs", "docs/openapi.yaml")], "s7")
        finally:
            shutil.rmtree(os.path.dirname(repo), ignore_errors=True)
    workflow_paths_tests()
    properties_format_tests()
    for f in failures:
        print("FAIL", f)
    print(f"{passed} passed, {len(failures)} failed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
