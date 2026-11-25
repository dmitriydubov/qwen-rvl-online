#!/usr/bin/env bash
# Initializes or updates an isolated single-vendor repository from the current rvl-online checkout.
# Usage:
#   gauntlet/adapters/sync_vendor_repos.sh <neon|codex|grok|kimi|qwen> <git-remote-url>
# Example (run from a clean main checkout of rvl-online after merging H1/H3):
#   for v in neon codex grok kimi qwen; do
#     gauntlet/adapters/sync_vendor_repos.sh "$v" "git@github.com:dmitriydubov/${v}-rvl-online.git"
#   done
set -euo pipefail

vendor="${1:?Usage: $0 <neon|codex|grok|kimi|qwen> <remote-url>}"
remote="${2:?Usage: $0 <neon|codex|grok|kimi|qwen> <remote-url>}"
src_root="$(git rev-parse --show-toplevel)"
if [ "$(git -C "$src_root" rev-parse --is-shallow-repository)" = "true" ]; then
  git -C "$src_root" fetch --quiet --unshallow origin
fi
full_sha="$(git -C "$src_root" rev-parse HEAD)"
base_sha="$(git -C "$src_root" rev-parse --short HEAD)"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/rvl-vendor-${vendor}.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

git clone --quiet --no-local "$src_root" "$tmp_dir/repo"
cd "$tmp_dir/repo"
git checkout -q -B main "$full_sha"

if [ "$vendor" != "neon" ]; then
  git rm -rf --quiet --ignore-unmatch GEMINI.md .agents
fi

case "$vendor" in
  neon)
    # GEMINI.md + .agents/ are already active on main
    ;;
  codex)
    cp -a gauntlet/adapters/codex/overlay/.codex .
    cp -a gauntlet/adapters/codex/overlay/AGENTS.md .
    ;;
  grok)
    cp -a gauntlet/adapters/grok/overlay/.grok .
    ln -sf ../../gauntlet/CLAUDE.md .grok/rules/10-gauntlet-kit.md
    ;;
  kimi)
    mkdir -p .kimi-code
    ln -sf ../gauntlet/adapters/kimi/AGENTS.md .kimi-code/AGENTS.md
    ;;
  qwen)
    cp -a gauntlet/adapters/qwen/overlay/.qwen .
    cp -a gauntlet/adapters/qwen/overlay/QWEN.md .
    ;;
  *)
    echo "Unknown vendor: $vendor (expected neon|codex|grok|kimi|qwen)" >&2
    exit 2
    ;;
esac

if git ls-remote --exit-code "$remote" refs/heads/main >/dev/null 2>&1; then
  git fetch --quiet "$remote" refs/heads/main:refs/remotes/vendor/main
  remote_main="$(git rev-parse refs/remotes/vendor/main)"
else
  remote_main=""
fi

if [ -n "$remote_main" ]; then
  git add -A -f
  tree_oid="$(git write-tree)"
  if [ "$tree_oid" = "$(git rev-parse "${remote_main}^{tree}")" ]; then
    echo "[ok] ${vendor} -> ${remote} already up to date (${remote_main:0:8})"
    exit 0
  fi
  if [ "$vendor" = "neon" ] && [ -z "$(git status --porcelain)" ] && git merge-base --is-ancestor "$remote_main" "$full_sha"; then
    target_commit="$full_sha"
  elif git merge-base --is-ancestor "$remote_main" "$full_sha"; then
    target_commit="$(git commit-tree "$tree_oid" -p "$full_sha" -m "chore: activate ${vendor} adapter on base ${base_sha}. By coordinator.")"
  else
    target_commit="$(git commit-tree "$tree_oid" -p "$remote_main" -p "$full_sha" -m "chore: sync ${vendor} adapter with base ${base_sha}. By coordinator.")"
  fi
  git push "$remote" "${target_commit}:refs/heads/main"
  echo "[ok] ${vendor} -> ${remote} fast-forwarded (${remote_main:0:8} -> ${target_commit:0:8}, base ${base_sha})"
else
  if [ -n "$(git status --porcelain)" ]; then
    git add -A -f
    git commit -q -m "chore: activate ${vendor} adapter on base ${base_sha}. By coordinator."
  fi
  git push --force "$remote" HEAD:refs/heads/main
  echo "[ok] ${vendor} -> ${remote} initialized (base ${base_sha}, head $(git rev-parse --short HEAD))"
fi
