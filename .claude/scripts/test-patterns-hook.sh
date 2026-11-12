#!/usr/bin/env bash
# PostToolUse hook (Edit|Write): after test code (*/src/test/*.kt) was written, run check-tests.sh on the new text only.
# An Edit's new_string repeats unchanged context, so a finding counts only when its line (trimmed) is not in the old
# text: old_string of an Edit, or the last committed version of a tracked file that a Write replaces (a new file: all
# lines count). Line numbers in the report are those of the file. The text is located in the file also when it starts
# or ends inside a line; the full lines of its range are then checked, so a comment above counts. A finding on a line
# that the text covers only partly is kept only when the written part of that line (without the untouched start and
# end) gives it on its own, with the lines above as context.
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

# Where the written text is in the file (the hook runs after the write), so that the lines above it give the context:
# a comment above a sleep, an open poll loop. A text is located by whole lines when it can be; else its first line may
# be the end of a file line and its last line the start of one (an Edit that starts or ends inside a line), the lines
# between being equal; a one-line text may be any part of a line. A text that starts (ends) with a newline begins
# (ends) at a line boundary: its empty first (last) line is not part of the range. Found exactly once: the file's full
# lines of that range are checked, with the file's line numbers. Not found, or found more than once: the text is
# checked on its own. The awk prints: first line, last line, untouched chars before the text on its first line,
# untouched chars after the text on its last line.
range="$(awk 'NR == FNR { text[++m] = $0; next }
  { file[++k] = $0 }
  function ends(line, part) { return length(part) <= length(line) && substr(line, length(line) - length(part) + 1) == part }
  function starts(line, part) { return substr(line, 1, length(part)) == part }
  function fits(line, part, atStart, atEnd) {
    if (atStart && atEnd) return line == part
    if (atStart) return starts(line, part)
    if (atEnd) return ends(line, part)
    return index(line, part) > 0
  }
  END {
    lo = 1; hi = m; loBound = 0; hiBound = 0
    if (m >= 2 && text[1] == "") { lo = 2; loBound = 1 }
    if (m >= 2 && text[m] == "") { hi = m - 1; hiBound = 1 }
    if (lo > hi) exit
    n = hi - lo + 1
    for (i = 1; i <= k - n + 1; i++) {
      ok = 1
      for (j = 0; ok && j < n; j++) {
        if (!fits(file[i + j], text[lo + j], j > 0 || loBound, j < n - 1 || hiBound)) ok = 0
      }
      if (!ok) continue
      if (n == 1) {
        if (hiBound && !loBound) p = length(file[i]) - length(text[lo])
        else if (loBound) p = 0
        else p = index(file[i], text[lo]) - 1
        q = length(file[i]) - p - length(text[lo])
      } else {
        p = loBound ? 0 : length(file[i]) - length(text[lo])
        q = hiBound ? 0 : length(file[i + n - 1]) - length(text[hi])
      }
      if (p == 0 && q == 0) { exact++; exactRange = i " " (i + n - 1) " 0 0" }
      else { loose++; looseRange = i " " (i + n - 1) " " p " " q }
    }
    if (exact == 1) print exactRange
    else if (exact == 0 && loose == 1) print looseRange
  }' <(printf '%s\n' "$text") "$path" 2>/dev/null)" || range=""
read -r start end before after <<<"$range"
skip=$(( ${start:-1} - 1 ))

if [ -n "$start" ]; then
  raw="$(sed -n "1,${end}p" "$path" | "$(dirname "$0")/check-tests.sh" --new-text "$path" --skip "$skip" 2>/dev/null | grep -v '^Total:')" || true
else
  raw="$(printf '%s\n' "$text" | "$(dirname "$0")/check-tests.sh" --new-text "$path" --skip 0 2>/dev/null | grep -v '^Total:')" || true
fi
[ -n "$raw" ] || exit 0

oldLines="$(printf '%s\n' "$oldText" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
findings=""
sleepHint=0; inputHint=0; interactHint=0
while IFS= read -r finding; do
  rest="${finding#"$path":}"
  number="${rest%%:*}"
  if [ -n "$start" ]; then source="$(sed -n "${number}p" "$path")"; else source="$(printf '%s\n' "$text" | sed -n "${number}p")"; fi
  line="$(printf '%s\n' "$source" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  # A finding on a line that the text covers only partly is kept only when the written part of the line gives it on its
  # own: check-tests.sh runs on the lines above plus the line without its untouched start and end. So a sleep before an
  # edited call is dropped, and a new sleep next to an old one is kept.
  if [ -n "$start" ]; then
    pre=0; suf=0
    if [ "$number" -eq "$start" ]; then pre="$before"; fi
    if [ "$number" -eq "$end" ]; then suf="$after"; fi
    if [ "$pre" -gt 0 ] || [ "$suf" -gt 0 ]; then
      written="${source:pre:${#source}-pre-suf}"
      if ! { sed -n "1,$((number - 1))p" "$path"; printf '%s\n' "$written"; } \
        | "$(dirname "$0")/check-tests.sh" --new-text "$path" --skip "$((number - 1))" 2>/dev/null | grep -qxF -- "$finding"; then
        continue
      fi
    fi
  fi
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

[ -n "$start" ] || echo "Line numbers are within the written text, not the file." >&2
printf '%s' "$findings" >&2
[ "$sleepHint" -eq 0 ] || echo "Sleep: wait on a signal instead, or add a // comment saying why there is none to wait on." >&2
[ "$inputHint" -eq 0 ] || echo "OS input: fire events at the node or change state in robot.interact { } (docs/testing.md); if only real input can test it, add a // comment saying why." >&2
[ "$interactHint" -eq 0 ] || echo "interact: drop the extra waitForFxEvents(), interact already waits." >&2
exit 2
