#!/usr/bin/env bash
# Reports test code that breaks the rules of docs/testing.md ("Writing UI tests"), as `file:line: reason`, then a last
# line with the number of findings per reason. Report only: the exit status is 0 whatever it finds.
#   - fixed sleep without a reason: Thread.sleep( or WaitForAsyncUtils.sleep( with no // comment on the same or the
#     previous line (wait on a signal instead, or say why there is none); a // inside a string literal is no comment.
#     The interval of a poll is no fixed sleep: a sleep on a line with `while (`, or inside a block opened on a line
#     with `while` or `do` (blocks nested in the loop count as inside it; `for` and `repeat` do not)
#   - synthetic OS input: clickOn, rightClickOn, doubleClickOn, robot.moveTo/type/write/scroll/drag (a bare moveTo
#     is mostly Path.moveTo or a control method, so it is not reported), unless a // comment on the same or the
#     previous line says why real input is needed
#   - interact already waits: waitForFxEvents() right after a one-line `interact { ... }`
#   - test class over 800 lines: split by topic
# Usage: .claude/scripts/check-tests.sh [files...]   (default: every tracked */src/test/*.kt file)
#        .claude/scripts/check-tests.sh --new-text <path> [--skip <n>]   reads the text to check from stdin and
#        reports it under <path>, without the size check (used by test-patterns-hook.sh); the first <n> lines are the
#        file's lines above the written text: they are read for context (comments, open loops) but not reported
set -euo pipefail
cd "$(dirname "$0")/../.."

newText=""
skip=0
if [ "${1:-}" = "--new-text" ]; then
  [ "$#" -eq 2 ] || { [ "$#" -eq 4 ] && [ "$3" = "--skip" ]; } || { echo "Usage: $0 --new-text <path> [--skip <n>]" >&2; exit 1; }
  newText="$2"
  [ "$#" -eq 2 ] || skip="$4"
  set -- -
elif [ "$#" -eq 0 ]; then
  files=()
  while IFS= read -r file; do files+=("$file"); done < <(git ls-files '*/src/test/*.kt')
  set -- "${files[@]}"
fi

awk -v newText="$newText" -v skip="$skip" '
function report(reason, line) {
  if (newText != "" && line <= skip) return
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
# Follows the braces of a line (strings and // comments removed): a block opened on a loop line counts as a loop.
function trackBlocks(code, loopLine,   i, c) {
  for (i = 1; i <= length(code); i++) {
    c = substr(code, i, 1)
    if (c == "{") {
      depth++; isLoop[depth] = loopLine; openLoops += loopLine
    } else if (c == "}" && depth > 0) {
      openLoops -= isLoop[depth]; depth--
    }
  }
}
function endOfFile() {
  if (newText == "" && lastLine > 800) report("test class over 800 lines; split by topic", 1)
}
FNR == 1 {
  if (NR > 1) endOfFile()
  currentFile = FILENAME
  previous = ""; previousOneLineInteract = 0
  depth = 0; openLoops = 0
}
{
  lastLine = FNR
  code = $0
  stripped = withoutStrings(code)
  trimmed = code; sub(/^[ \t]+/, "", trimmed)
  isComment = (trimmed ~ /^(\/\/|\*|\/\*)/)
  reasoned = (stripped ~ /\/\// || previous ~ /\/\//)
  codeOnly = stripped; sub(/\/\/.*/, "", codeOnly)
  if (isComment) codeOnly = ""
  loopLine = (codeOnly ~ /(^|[^A-Za-z0-9_])while[ \t]*\(/ || codeOnly ~ /(^|[^A-Za-z0-9_])do[ \t]*\{/) ? 1 : 0

  if (!isComment && code ~ /(Thread|WaitForAsyncUtils)\.sleep\(/ && !reasoned && !loopLine && openLoops == 0) {
    report("fixed sleep without a reason", FNR)
  }
  if (!isComment && !reasoned && code ~ /\.(clickOn|rightClickOn|doubleClickOn)\(|robot\.(moveTo|type|write|scroll|drag)\(/) {
    report("synthetic OS input (docs/testing.md)", FNR)
  }
  if (previousOneLineInteract && code ~ /waitForFxEvents\(\)/) {
    report("interact already waits", FNR)
  }
  previousOneLineInteract = (!isComment && code ~ /(^|[^A-Za-z0-9_])interact[ \t]*\{.*\}[ \t]*$/ && braceBalance(code) == 0)
  trackBlocks(codeOnly, loopLine)
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
