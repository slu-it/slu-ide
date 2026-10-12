#!/usr/bin/env bash
# PostToolUse hook (Edit|Write): after a file below docs/ changed, verify docs/index.md still lists every document.
# Exit code 2 feeds the message back to Claude; anything unrelated to docs/ is ignored.
input="$(cat)"
case "$input" in
  *'/docs/'*) ;;
  *) exit 0 ;;
esac

if ! output="$("$(dirname "$0")/check-docs-index.sh" 2>&1)"; then
  echo "$output" >&2
  echo "Add the missing documents to docs/index.md with a one-line summary." >&2
  exit 2
fi
