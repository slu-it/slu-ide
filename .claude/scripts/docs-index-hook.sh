#!/usr/bin/env bash
# PostToolUse hook (Edit|Write): after a file below docs/ changed, verify docs/index.md still lists every document.
# Looks at tool_input.file_path only: the file must be below the top-level docs/ folder of the git work tree that
# contains it (a linked worktree under .claude/worktrees/ is its own tree). That tree's
# .claude/scripts/check-docs-index.sh runs; a tree without one is ignored (the hook's own copy would check another
# tree). Exit code 2 feeds the message back to Claude; a missing field, invalid JSON, a path outside a git tree or any
# other file is ignored (exit 0).
input="$(cat)" || exit 0

# The value of tool_input.file_path ("" when missing), with jq if available, else python3.
if command -v jq >/dev/null 2>&1; then
  path="$(printf '%s' "$input" | jq -r '.tool_input.file_path // ""' 2>/dev/null)" || exit 0
else
  path="$(printf '%s' "$input" | python3 -I -c '
import json, sys
print((json.load(sys.stdin).get("tool_input") or {}).get("file_path") or "")
' 2>/dev/null)" || exit 0
fi
[ -n "$path" ] || exit 0

dir="$(cd "$(dirname "$path")" 2>/dev/null && pwd -P)" || exit 0
top="$(git -C "$dir" rev-parse --show-toplevel 2>/dev/null)" || exit 0
[ -n "$top" ] || exit 0
file="$dir/$(basename "$path")"
case "$file" in
  "$top"/docs/*) ;;
  *) exit 0 ;;
esac

check="$top/.claude/scripts/check-docs-index.sh"
[ -x "$check" ] || exit 0
if ! output="$(cd "$top" && "$check" 2>&1)"; then
  echo "$output" >&2
  echo "Add the missing documents to docs/index.md as table rows with a short summary and keywords." >&2
  exit 2
fi
