#!/usr/bin/env bash
# PostToolUse hook (Edit|Write): after test code (*/src/test/*.kt) was written, run check-tests.sh on the new text only.
# An Edit's new_string repeats unchanged context, so a finding counts only when its line (trimmed) is not in the old
# text: old_string of an Edit, or the last committed version of a tracked file that a Write replaces (a new file: all
# lines count). Line numbers in the report are within the written text.
# Exit code 2 feeds the findings back to Claude; unrelated files and unreadable input are ignored (exit 0).
input="$(cat)" || exit 0
case "$input" in
  *'/src/test/'*) ;;
  *) exit 0 ;;
esac

# The value of tool_input.<name> ("" when missing), with jq if available, else python3.
field() {
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$input" | jq -r --arg name "$1" '.tool_input[$name] // ""' 2>/dev/null
  else
    printf '%s' "$input" | python3 -I -c '
import json, sys
print((json.load(sys.stdin).get("tool_input") or {}).get(sys.argv[1]) or "")
' "$1" 2>/dev/null
  fi
}

path="$(field file_path)" || exit 0
case "$path" in
  */src/test/*.kt) ;;
  *) exit 0 ;;
esac
oldText="$(field old_string)" || exit 0
text="$(field new_string)" || exit 0
if [ -z "$text" ]; then
  text="$(field content)" || exit 0
  # A Write: compare against the committed version of the file, if there is one.
  oldText="$(git -C "$(dirname "$path")" show "HEAD:./$(basename "$path")" 2>/dev/null)" || oldText=""
fi
[ -n "$text" ] || exit 0

# The line above an Edit's text, so that a comment there counts for a sleep on the first line.
contextBefore=""
if [ -n "$oldText" ] && [ -f "$path" ]; then
  first="${oldText%%$'\n'*}"
  number="$(grep -nF -m1 -- "$first" "$path" | cut -d: -f1)" || number=""
  if [ -n "$number" ] && [ "$number" -gt 1 ]; then
    contextBefore="$(sed -n "$((number - 1))p" "$path")"
  fi
fi

raw="$(printf '%s\n' "$text" | "$(dirname "$0")/check-tests.sh" --new-text "$path" --context-before "$contextBefore" 2>/dev/null | grep -v '^Total:')" || true
[ -n "$raw" ] || exit 0

oldLines="$(printf '%s\n' "$oldText" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
findings=""
sleepHint=0; inputHint=0; interactHint=0
while IFS= read -r finding; do
  rest="${finding#"$path":}"
  number="${rest%%:*}"
  line="$(printf '%s\n' "$text" | sed -n "${number}p" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  if [ -n "$oldText" ] && printf '%s\n' "$oldLines" | grep -qxF -- "$line"; then
    continue
  fi
  findings="$findings$finding"$'\n'
  case "$finding" in
    *'fixed sleep'*) sleepHint=1 ;;
    *'synthetic OS input'*) inputHint=1 ;;
    *'interact already waits'*) interactHint=1 ;;
  esac
done <<<"$raw"
[ -n "$findings" ] || exit 0

echo "Line numbers are within the written text, not the file." >&2
printf '%s' "$findings" >&2
[ "$sleepHint" -eq 0 ] || echo "Sleep: wait on a signal instead, or add a // comment saying why there is none to wait on." >&2
[ "$inputHint" -eq 0 ] || echo "OS input: fire events at the node or change state in robot.interact { } (docs/testing.md)." >&2
[ "$interactHint" -eq 0 ] || echo "interact: drop the extra waitForFxEvents(), interact already waits." >&2
exit 2
