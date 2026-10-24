#!/usr/bin/env bash
# Reports test code that breaks the rules of docs/testing.md ("Writing UI tests"), as `file:line: reason`, then a last
# line with the number of findings per reason. Report only: the exit status is 0 whatever it finds.
#   - fixed sleep without a reason: Thread.sleep( or WaitForAsyncUtils.sleep( with no // comment on the same or the
#     previous line (wait on a signal instead, or say why there is none); a // inside a string literal is no comment
#   - synthetic OS input: clickOn, rightClickOn, doubleClickOn, robot.moveTo/type/write/scroll/drag (a bare moveTo
#     is mostly Path.moveTo or a control method, so it is not reported)
#   - interact already waits: waitForFxEvents() right after a one-line `interact { ... }`
#   - test class over 800 lines: split by topic
# Usage: .claude/scripts/check-tests.sh [files...]   (default: every tracked */src/test/*.kt file)
#        .claude/scripts/check-tests.sh --new-text <path> [--context-before <line>]   reads the text to check from
#        stdin and reports it under <path> with the line numbers of that text, without the size check (used by
#        test-patterns-hook.sh); --context-before is the line above the text, so that a sleep on its first line can
#        see the comment above it
set -euo pipefail
cd "$(dirname "$0")/../.."

newText=""
contextBefore=""
if [ "${1:-}" = "--new-text" ]; then
  [ "$#" -eq 2 ] || [ "$#" -eq 4 ] || { echo "Usage: $0 --new-text <path> [--context-before <line>]" >&2; exit 1; }
  newText="$2"
  [ "${3:-}" != "--context-before" ] || contextBefore="$4"
  set -- -
elif [ "$#" -eq 0 ]; then
  files=()
  while IFS= read -r file; do files+=("$file"); done < <(git ls-files '*/src/test/*.kt')
  set -- "${files[@]}"
fi

awk -v newText="$newText" -v contextBefore="$contextBefore" '
function report(reason, line) {
  print (newText != "" ? newText : currentFile) ":" line ": " reason
  total[reason]++
}
function braceBalance(text,   opens, closes, copy) {
  copy = text; opens = gsub(/\{/, "", copy)
  copy = text; closes = gsub(/\}/, "", copy)
  return opens - closes
}
function withoutStrings(text) {
  gsub(/"([^"\\]|\\.)*"/, "", text)
  return text
}
function endOfFile() {
  if (newText == "" && lastLine > 800) report("test class over 800 lines; split by topic", 1)
}
FNR == 1 {
  if (NR > 1) endOfFile()
  currentFile = FILENAME
  previous = (newText != "" ? withoutStrings(contextBefore) : ""); previousOneLineInteract = 0
}
{
  lastLine = FNR
  code = $0
  stripped = withoutStrings(code)
  trimmed = code; sub(/^[ \t]+/, "", trimmed)
  isComment = (trimmed ~ /^(\/\/|\*|\/\*)/)

  if (!isComment && code ~ /(Thread|WaitForAsyncUtils)\.sleep\(/ && stripped !~ /\/\// && previous !~ /\/\//) {
    report("fixed sleep without a reason", FNR)
  }
  if (!isComment && code ~ /\.(clickOn|rightClickOn|doubleClickOn)\(|robot\.(moveTo|type|write|scroll|drag)\(/) {
    report("synthetic OS input (docs/testing.md)", FNR)
  }
  if (previousOneLineInteract && code ~ /waitForFxEvents\(\)/) {
    report("interact already waits", FNR)
  }
  previousOneLineInteract = (!isComment && code ~ /(^|[^A-Za-z0-9_])interact[ \t]*\{.*\}[ \t]*$/ && braceBalance(code) == 0)
  previous = stripped
}
END {
  if (NR > 0) endOfFile()
  line = "Total:"
  n = split("fixed sleep without a reason|synthetic OS input (docs/testing.md)|interact already waits|test class over 800 lines; split by topic", reasons, "|")
  for (i = 1; i <= n; i++) line = line (i > 1 ? "," : "") " " (reasons[i] in total ? total[reasons[i]] : 0) " " reasons[i]
  print line
}
' "$@"
