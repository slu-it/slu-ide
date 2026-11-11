#!/usr/bin/env bash
# Lists the test classes that cover changed code, grouped by module and Gradle task (test or uiTest), so that a
# change can be verified with .claude/scripts/verify.sh without reading the tests.
# Input: the given files, or by default every .kt file changed against master (committed, uncommitted, untracked).
# A changed file under */src/main/ stands for the types it declares at top level plus its base name; a changed test
# file counts itself. A name that appears as a whole word in more than 25 test files is too common and is skipped.
# Test helper files without @Test (fixtures) are left out. Always exits 0; prints `no related tests` when empty.
# Usage: .claude/scripts/related-tests.sh [--names] [files...]
#   --names   print only the class names, one per line (the input of verify.sh)
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd -P)"
cd "$root"

names_only=0
if [ "${1:-}" = "--names" ]; then
  names_only=1
  shift
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if [ "$#" -gt 0 ]; then
  printf '%s\n' "$@" >"$work/input"
else
  {
    git diff --name-only master...HEAD 2>/dev/null || true
    git diff --name-only HEAD 2>/dev/null || true
    git ls-files --others --exclude-standard
  } >"$work/input"
fi
# Existing .kt files, relative to the root, without duplicates.
while IFS= read -r f; do
  f="${f#"$root"/}"
  case "$f" in *.kt) if [ -f "$f" ]; then echo "$f"; fi ;; esac
done <"$work/input" | sort -u >"$work/changed"

{ git ls-files '*/src/test/*.kt'; git ls-files --others --exclude-standard '*/src/test/*.kt'; } | sort -u >"$work/tests.all"
while IFS= read -r f; do if [ -f "$f" ]; then echo "$f"; fi; done <"$work/tests.all" >"$work/tests"

: >"$work/related"
: >"$work/skipped"
while IFS= read -r f; do
  case "$f" in
    */src/test/*) echo "$f" >>"$work/related" ;;
    */src/main/*)
      # Convention: Foo.kt is tested by FooTest.kt, FooUiTest.kt; the word search cannot see those names.
      base="$(basename "$f" .kt)"
      grep -E "^${f%%/src/*}/src/test/.*/${base}[A-Za-z0-9]*\.kt$" "$work/tests" >>"$work/related" || true
      {
        echo "$base"
        # Private declarations are not API: other files (tests included) cannot refer to them.
        grep -vE '^([a-z]+[[:space:]]+)*private[[:space:]]' "$f" |
          sed -nE 's/^((public|internal|private|protected|data|sealed|enum|abstract|open|annotation|value|inline|fun|const|companion)[[:space:]]+)*(class|interface|object|typealias)[[:space:]]+`?([A-Za-z_][A-Za-z0-9_]*).*/\4/p'
      } | sort -u | while IFS= read -r name; do
        [ -s "$work/tests" ] || continue
        # NUL-separated so that paths with blanks stay intact; grep exits 1 without a match.
        matches="$(tr '\n' '\0' <"$work/tests" | xargs -0 grep -lwF -- "$name" 2>/dev/null || true)"
        [ -n "$matches" ] || continue
        count="$(printf '%s\n' "$matches" | wc -l | tr -d ' ')"
        if [ "$count" -gt 25 ]; then
          echo "skipped $name: $count test files" >>"$work/skipped"
        else
          printf '%s\n' "$matches" >>"$work/related"
        fi
      done
      ;;
  esac
done <"$work/changed"
sort -u "$work/skipped" -o "$work/skipped"

# module path <TAB> task <TAB> class, only files with a test method.
sort -u "$work/related" | while IFS= read -r f; do
  grep -qE '@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b' "$f" || continue
  module="${f%%/src/*}"
  gpath=":$(echo "$module" | tr / :)"
  task=test
  if grep -q '^@Tag("ui")' "$f"; then task=uiTest; fi
  # The test class Gradle knows: the top-level class that holds the first test method (ShotSearch.kt declares a helper
  # class ShotSearch and the test class ShotSearchTest), else the file's base name.
  class="$(awk '
    match($0, /^((internal|abstract|open|data)[ \t]+)*class[ \t]+[A-Za-z_][A-Za-z0-9_]*/) {
      decl = substr($0, RSTART, RLENGTH); sub(/.*class[ \t]+/, "", decl); last = decl
    }
    /@(Test|ParameterizedTest|RepeatedTest|TestFactory)([^A-Za-z0-9_]|$)/ { if (last != "") { print last; exit } }
  ' "$f")"
  printf '%s\t%s\t%s\n' "$gpath" "$task" "${class:-$(basename "$f" .kt)}"
done | sort -u >"$work/classes"

if [ ! -s "$work/classes" ]; then
  if [ "$names_only" -eq 0 ]; then
    echo "no related tests"
    cat "$work/skipped"
  fi
  exit 0
fi

if [ "$names_only" -eq 1 ]; then
  cut -f3 "$work/classes" | sort -u
  exit 0
fi

cut -f1,2 "$work/classes" | sort -u | while IFS=$'\t' read -r gpath task; do
  list="$(awk -F'\t' -v m="$gpath" -v t="$task" '$1 == m && $2 == t { print $3 }' "$work/classes" | paste -sd, - | sed 's/,/, /g')"
  printf '%s  %s: %s\n' "$gpath" "$task" "$list"
done
cat "$work/skipped"
echo "run: .claude/scripts/verify.sh $(cut -f3 "$work/classes" | sort -u | paste -sd' ' -)"
