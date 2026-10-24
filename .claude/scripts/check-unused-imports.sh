#!/usr/bin/env bash
# Lists Kotlin imports that nothing in the file uses, as `file:line: Name`, and exits 1 if there are any.
# ktlint 1.8.0 (Spotless) has a no-unused-imports rule, but it leaves them in place here even when enabled in
# .editorconfig, so this script does the check. An import counts as used when its simple name (or alias) appears as a
# whole word anywhere outside the import lines, KDoc and comments included. Star imports and imports that Kotlin
# resolves by convention (operators, delegates, destructuring) are skipped, as they are used without their name.
# Usage: .claude/scripts/check-unused-imports.sh [files...]   (default: every tracked *.kt file)
set -euo pipefail
cd "$(dirname "$0")/../.."

if [ "$#" -eq 0 ]; then
  files=()
  while IFS= read -r file; do files+=("$file"); done < <(git ls-files '*.kt')
  set -- "${files[@]}"
fi

# One awk process for all files: each file is read once, its words go into a set, every import is looked up in it.
awk '
function finish(   i) {
  for (i = 1; i <= count; i++) {
    if (!(names[i] in words)) { print file ":" lines[i] ": " names[i]; found = 1 }
  }
}
BEGIN {
  n = split("getValue setValue provideDelegate invoke get set contains compareTo plus minus times div rem rangeTo rangeUntil iterator next hasNext component1 component2 component3 component4 component5 component6 component7 component8 component9 not unaryMinus unaryPlus inc dec plusAssign minusAssign", skipList, " ")
  for (i = 1; i <= n; i++) skip[skipList[i]] = 1
}
FNR == 1 {
  if (NR > 1) finish()
  file = FILENAME; count = 0
  delete words; delete names; delete lines
}
/^import / {
  line = $0
  sub(/^import +/, "", line)
  sub(/[ \t]*(\/\/.*)?;?[ \t]*$/, "", line)
  name = line
  if (line ~ / as /) sub(/.* as +/, "", name); else sub(/.*\./, "", name)
  gsub(/`/, "", name)
  if (name != "*" && !(name in skip)) { count++; names[count] = name; lines[count] = FNR }
  next
}
{
  n = split($0, parts, /[^A-Za-z0-9_]+/)
  for (i = 1; i <= n; i++) if (parts[i] != "") words[parts[i]] = 1
}
END { if (NR > 0) finish(); exit found }
' "$@"
