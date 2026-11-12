#!/usr/bin/env bash
# Lists the test classes that cover changed code, grouped by module and Gradle task (test or uiTest), so that a
# change can be verified with .claude/scripts/verify.sh without reading the tests.
# Input: the given files, or by default every .kt file changed against master (committed, uncommitted, untracked).
# A changed file under */src/main/ stands for the types it declares at top level, its non-private top-level functions
# (extension functions included, declared at column 0; not properties) plus its base name (a changed main
# resource for the main .kt files of its module that name it); a changed test
# file counts itself. Kotlin's visibility decides where a name is searched: a name declared `internal` only in the
# test files of the changed file's own module (<module>/src/test/), any other name (and the base name) in the test
# files of all modules; private declarations are skipped. A name that appears as a whole word in more than 25 of the
# test files searched is too common and is skipped.
# Test helper files without @Test (fixtures) are left out. Always exits 0 (also --test-classes); prints
# `no related tests` when empty.
# A given path that does not exist is reported as `not found: <path>` on stderr (not for the default input, which
# lists deleted files); paths may be absolute or relative to the repository root.
# Names skipped as too common are listed as `skipped <name>: <n> test files`: on stdout, or with --names on stderr.
# Usage: .claude/scripts/related-tests.sh [--names] [files...]
#        .claude/scripts/related-tests.sh --test-classes   (file paths on stdin)
#   --names   print only the class names, one per line (the input of verify.sh)
#   --test-classes   for verify.sh: prints `path<TAB>test class<TAB>1 or 0 (has a test method)` for each existing path
#             read from stdin. The test class is the one Gradle knows: the top-level class or object that holds the
#             first test annotation (ShotSearch.kt declares a helper class ShotSearch and the test class ShotSearchTest; a
#             KDoc that mentions @Test, not at the start of its line, does not count), else the file's base name. This
#             is the one definition of it; verify.sh uses it to match targets against class names.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd -P)"
cd "$root"

# One awk pass over many files (NUL-separated on stdin): path, test class, 1 if the file has a test method else 0.
# A test annotation counts only as the first thing on its line (so a KDoc or comment that mentions @Test does not); one
# seen before any class waits for the next class; a file with a test annotation but no recognizable class falls back
# to its base name.
test_class_index() {
  xargs -0 awk '
    function finish() {
      if (file != "") print file "\t" (cls != "" ? cls : base) "\t" (seen ? 1 : 0)
    }
    FNR == 1 {
      finish()
      file = FILENAME; base = FILENAME; sub(/.*\//, "", base); sub(/\.kt$/, "", base)
      last = ""; cls = ""; seen = 0
    }
    cls != "" { next }
    match($0, /^(@[A-Za-z]+(\([^)]*\))?[ \t]+)*((public|internal|abstract|open|data|final)[ \t]+)*(class|object)[ \t]+[A-Za-z_][A-Za-z0-9_]*/) {
      decl = substr($0, RSTART, RLENGTH); sub(/^.*[ \t]*(class|object)[ \t]+/, "", decl); last = decl
      if (seen) cls = last
    }
    /^[ \t]*@(Test|ParameterizedTest|RepeatedTest|TestFactory)([^A-Za-z0-9_(]|\(|$)/ { seen = 1; if (last != "") cls = last }
    END { finish() }
  '
}

if [ "${1:-}" = "--test-classes" ]; then
  # Paths that do not exist are left out.
  while IFS= read -r f; do if [ -f "$f" ]; then echo "$f"; fi; done | tr '\n' '\0' | test_class_index
  exit 0
fi

names_only=0
if [ "${1:-}" = "--names" ]; then
  names_only=1
  shift
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

explicit=0
if [ "$#" -gt 0 ]; then
  explicit=1
  printf '%s\n' "$@" >"$work/input"
else
  {
    git diff --name-only master...HEAD 2>/dev/null || true
    git diff --name-only HEAD 2>/dev/null || true
    git ls-files --others --exclude-standard
  } >"$work/input"
fi
# Existing .kt files, relative to the root, without duplicates. A changed main resource (a script, a stylesheet)
# stands for the main .kt files of its module that name it, since its tests rarely name the resource itself.
while IFS= read -r f; do
  f="${f#"$root"/}"
  if [ ! -f "$f" ]; then
    # Only for given paths: the default input legitimately lists deleted files.
    if [ "$explicit" -eq 1 ]; then echo "not found: $f" >&2; fi
    continue
  fi
  case "$f" in
    *.kt) echo "$f" ;;
    */src/main/resources/*)
      grep -rlF --include='*.kt' -- "$(basename "$f")" "${f%%/src/*}/src/main" 2>/dev/null || true
      ;;
  esac
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
      # Private declarations are not API: other files (tests included) cannot refer to them.
      { grep -vE '^([a-z]+[[:space:]]+)*private[[:space:]]' "$f" || true; } >"$work/decls"
      declared='s/^((public|internal|protected|data|sealed|enum|abstract|open|annotation|value|inline|fun|const|companion)[[:space:]]+)*(class|interface|object|typealias)[[:space:]]+`?([A-Za-z_][A-Za-z0-9_]*).*/\4/p'
      functions='s/^((public|internal|protected|inline|suspend|operator|infix|tailrec|external|expect|actual)[[:space:]]+)*fun[[:space:]]+(<[^()]*>[[:space:]]+)?([^(]*\.)?`?([A-Za-z_][A-Za-z0-9_]*)`?[[:space:]]*\(.*/\5/p'
      internal_re='^([a-z]+[[:space:]]+)*internal[[:space:]]'
      module_tests="$(grep "^${f%%/src/*}/src/test/" "$work/tests" || true)"
      {
        printf 'public\t%s\n' "$base"
        { grep -vE "$internal_re" "$work/decls" || true; } | sed -nE -e "$declared" -e "$functions" | sed 's/^/public\t/'
        { grep -E "$internal_re" "$work/decls" || true; } | sed -nE -e "$declared" -e "$functions" | sed 's/^/internal\t/'
      } | sort -u | while IFS=$'\t' read -r scope name; do
        searched="$work/tests"
        if [ "$scope" = internal ]; then
          printf '%s\n' "$module_tests" >"$work/tests.module"
          searched="$work/tests.module"
        fi
        [ -s "$searched" ] || continue
        # NUL-separated so that paths with blanks stay intact; grep exits 1 without a match.
        matches="$(tr '\n' '\0' <"$searched" | xargs -0 grep -lwF -- "$name" 2>/dev/null || true)"
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
sort -u "$work/related" | tr '\n' '\0' | test_class_index | while IFS=$'\t' read -r f class hastest; do
  [ "$hastest" = 1 ] || continue
  module="${f%%/src/*}"
  gpath=":$(echo "$module" | tr / :)"
  task=test
  if grep -q '^@Tag("ui")' "$f"; then task=uiTest; fi
  printf '%s\t%s\t%s\n' "$gpath" "$task" "$class"
done | sort -u >"$work/classes"

if [ ! -s "$work/classes" ]; then
  if [ "$names_only" -eq 0 ]; then
    echo "no related tests"
    cat "$work/skipped"
  else
    cat "$work/skipped" >&2
  fi
  exit 0
fi

if [ "$names_only" -eq 1 ]; then
  cat "$work/skipped" >&2
  cut -f3 "$work/classes" | sort -u
  exit 0
fi

cut -f1,2 "$work/classes" | sort -u | while IFS=$'\t' read -r gpath task; do
  list="$(awk -F'\t' -v m="$gpath" -v t="$task" '$1 == m && $2 == t { print $3 }' "$work/classes" | paste -sd, - | sed 's/,/, /g')"
  printf '%s  %s: %s\n' "$gpath" "$task" "$list"
done
cat "$work/skipped"
echo "run: .claude/scripts/verify.sh $(cut -f3 "$work/classes" | sort -u | paste -sd' ' -)"
