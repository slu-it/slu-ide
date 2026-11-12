#!/usr/bin/env bash
# The test loop in one call: formats (spotlessApply), compiles everything, runs the chosen tests in one Gradle
# invocation and prints a short digest: compile errors, passed/failed/skipped per test task, each failure with its
# message and a few stack frames, then `verify: OK` or `verify: FAILED (log: ...)`. The full Gradle output is in
# build/verify/verify.log. Never run the unfiltered build with it; the main session does that after the last review.
# Targets:
#   :modules:todos   the unit `test` task of that module, unfiltered (`:ide` for the app)
#   DeleteConfirmationTest, 'MainWindow*'   a test class simple name or a glob over the names; a name matches the
#                    test file's base name or its test class (the class that holds the first test method, as in
#                    related-tests.sh --test-classes: ShotSearch.kt declares ShotSearch and ShotSearchTest, both
#                    targets run ShotSearchTest); it goes to uiTest when the file contains @Tag("ui"), else to test
# An unknown target, or a file without @Test, ends with exit 2 before Gradle starts.
# --changed adds the unit tests of every module with changed files (against master, uncommitted, untracked) and the
# classes `related-tests.sh --names` finds; more than 12 UI test classes stop the run (name classes instead).
# Names that related-tests.sh skipped as too common are shown on stderr (their tests in other modules are not run by
# --changed and must be named explicitly).
# --list resolves the targets (--changed included), prints one line per test class as
# `<gradle module path> <task> <fully qualified class or *>` and exits 0, before the format step, the display setup
# and Gradle (the check for another Gradle build does not apply to it).
# UI test classes on Linux get a virtual display from setup-display.sh (:N from --display, else $DISPLAY, else :99);
# if that fails they are reported as NOT RUN and the run ends INCOMPLETE (exit 1).
# Usage: .claude/scripts/verify.sh [--no-format] [--changed] [--list] [--display N] [target...] [-- gradle-args]
# Gradle arguments that skip tasks (`-- -x uiTest`) let old passing results of the skipped classes count: don't.
# The check for another Gradle build (gradle-running.sh) reads /proc, so it works on Linux only: a client in this tree
# (not one in a linked worktree below it) ends the run with exit 2 before anything starts.
# A uiTest that dies on the DisplayService cast error is rerun after `./gradlew --stop`, which stops the daemons of
# every tree: that happens only when no Gradle client runs anywhere (`gradle-running.sh --any`); otherwise the run
# ends `verify: FAILED` with the pid and directory of that client, and the daemons are left alone; the digest and the
# check-tests findings of that run still print. Without /proc (macOS) the helper finds nothing, so the daemons are
# always stopped there.
# Exit: Gradle's status; 2 for usage errors and refusals (another Gradle build running, unknown target).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd -P)"
cd "$root"

format=1
changed=0
list=0
display_number=""
targets=()
gradle_args=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --no-format) format=0 ;;
    --changed) changed=1 ;;
    --list) list=1 ;;
    --display)
      [ "$#" -ge 2 ] || { echo "--display needs a number" >&2; exit 2; }
      display_number="$2"
      shift
      ;;
    --) shift; gradle_args=("$@"); break ;;
    -*) echo "Unknown option $1. Usage: $0 [--no-format] [--changed] [--list] [--display N] [target...] [-- gradle-args]" >&2; exit 2 ;;
    *) targets+=("$1") ;;
  esac
  shift
done
if [ "${#targets[@]}" -eq 0 ] && [ "$changed" -eq 0 ]; then
  echo "No target. Usage: $0 [--no-format] [--changed] [--list] [--display N] [target...] [-- gradle-args]" >&2
  exit 2
fi
if [ -n "$display_number" ] && ! [[ "$display_number" =~ ^[0-9]+$ ]]; then
  echo "--display needs a number, not $display_number" >&2
  exit 2
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p build/verify
log="$root/build/verify/verify.log"
# Next to the XML results, so both timestamps come from the same file system and clock.
marker="$root/build/verify/marker"

# Files changed against master, committed or not, deleted ones included.
changed_paths() {
  {
    git diff --name-only master...HEAD 2>/dev/null || true
    git diff --name-only HEAD 2>/dev/null || true
    git ls-files --others --exclude-standard
  } | sort -u
}
# The same without the deleted ones.
changed_files() {
  changed_paths | while IFS= read -r f; do if [ -f "$f" ]; then echo "$f"; fi; done
}

# All test sources, tracked and untracked.
{ git ls-files '*/src/test/*.kt'; git ls-files --others --exclude-standard '*/src/test/*.kt'; } | sort -u |
  while IFS= read -r f; do if [ -f "$f" ]; then echo "$f"; fi; done >"$work/tests"

# Per test file: path, its test class, 1 if it has a test method (one definition, in related-tests.sh). Built on first
# use: one awk pass over all test files, so that matching a target costs no process per file.
test_index_built=0
build_test_index() {
  [ "$test_index_built" -eq 0 ] || return 0
  test_index_built=1
  "$root/.claude/scripts/related-tests.sh" --test-classes <"$work/tests" >"$work/test-index"
}

# Spec lines: module dir <TAB> task <TAB> fully qualified class, or * for the whole task.
: >"$work/specs"
add_target() {
  local target="$1" dir found=0 test_found=0 f base cls hastest pkg task
  case "$target" in
    :*)
      dir="$(echo "${target#:}" | tr : /)"
      if [ ! -f "$dir/build.gradle.kts" ]; then
        echo "Unknown module $target (no $dir/build.gradle.kts)" >&2
        exit 2
      fi
      printf '%s\ttest\t*\n' "$dir" >>"$work/specs"
      return
      ;;
  esac
  build_test_index
  while IFS=$'\t' read -r f cls hastest; do
    base="${f##*/}"; base="${base%.kt}"
    # shellcheck disable=SC2053
    [[ "$base" == $target || "$cls" == $target ]] || continue
    found=1
    [ "$hastest" = 1 ] || continue
    test_found=1
    pkg="$(sed -nE 's/^package[[:space:]]+([A-Za-z0-9_.]+).*/\1/p' "$f" | head -n 1)"
    task=test
    if grep -q '^@Tag("ui")' "$f"; then task=uiTest; fi
    printf '%s\t%s\t%s\n' "${f%%/src/*}" "$task" "${pkg:+$pkg.}$cls" >>"$work/specs"
  done <"$work/test-index"
  if [ "$found" -eq 0 ]; then
    echo "Unknown target $target: no test file with that name" >&2
    exit 2
  fi
  if [ "$test_found" -eq 0 ]; then
    echo "Target $target matches only files without @Test (a fixture?)" >&2
    exit 2
  fi
}

for t in ${targets[@]+"${targets[@]}"}; do add_target "$t"; done

if [ "$changed" -eq 1 ]; then
  # Unit tests of every module with changed files.
  # (also a module whose only change is a deleted file; modules that no longer exist are skipped)
  changed_paths | sed -nE 's|^(ide)/.*|\1|p; s|^(modules/[^/]+)/.*|\1|p' | sort -u |
    while IFS= read -r dir; do
      if [ -f "$dir/build.gradle.kts" ]; then printf '%s\ttest\t*\n' "$dir" >>"$work/specs"; fi
    done
  # Its stderr (names skipped as too common) goes to ours, with a hint.
  related="$("$root/.claude/scripts/related-tests.sh" --names 2>"$work/related.err" || true)"
  if [ -s "$work/related.err" ]; then
    cat "$work/related.err" >&2
    if grep -q '^skipped ' "$work/related.err"; then
      echo "Tests of the skipped names in other modules are not run by --changed (the unit tests of the changed modules still run): name them explicitly." >&2
    fi
  fi
  for name in $related; do add_target "$name"; done
  ui_count="$(awk -F'\t' '$2 == "uiTest"' "$work/specs" | sort -u | wc -l | tr -d ' ')"
  if [ "$ui_count" -gt 12 ]; then
    echo "--changed would run $ui_count UI test classes (limit 12):" >&2
    awk -F'\t' '$2 == "uiTest" { print "  " $3 }' "$work/specs" | sort -u >&2
    echo "Name the classes to run explicitly instead." >&2
    exit 2
  fi
fi
sort -u "$work/specs" -o "$work/specs"
if [ "$list" -eq 1 ]; then
  awk -F'\t' '{ gsub("/", ":", $1); print ":" $1 " " $2 " " $3 }' "$work/specs"
  exit 0
fi

# 1. Another Gradle client in this tree would share build/ with us (see full-build.sh).
if running="$("$root/.claude/scripts/gradle-running.sh" "$root")"; then
  echo "Another Gradle build runs in $root (pid ${running%% *}); wait for it to finish first." >&2
  exit 2
fi
no_tests=0
if [ ! -s "$work/specs" ]; then
  no_tests=1
  echo "no tests selected (compiled only)"
fi

in_worktree=0
if [ "$(git rev-parse --absolute-git-dir)" != "$(git rev-parse --path-format=absolute --git-common-dir)" ]; then
  in_worktree=1
fi
gradlew=(./gradlew)
if [ "$in_worktree" -eq 1 ]; then gradlew+=(--no-daemon); fi

# 2. Format. Changed files = Kotlin files whose content differs before and after.
snapshot() {
  { git diff --name-only HEAD; git ls-files --others --exclude-standard; } | sort -u | grep -E '\.kts?$' |
    while IFS= read -r f; do
      if [ -f "$f" ]; then echo "$(git hash-object "$f") $f"; fi
    done | sort || true
}
if [ "$format" -eq 1 ]; then
  snapshot >"$work/before"
  fstatus=0
  "${gradlew[@]}" spotlessApply -q >"$log" 2>&1 || fstatus=$?
  if [ "$fstatus" -ne 0 ]; then
    echo "format: FAILED (spotlessApply)"
    grep -vE '^\s*$' "$log" | head -n 30
    echo "verify: FAILED (log: $log)"
    exit "$fstatus"
  fi
  snapshot >"$work/after"
  reformatted="$(comm -13 "$work/before" "$work/after" | cut -d' ' -f2- | paste -sd, - | sed 's/,/, /g')"
  if [ -n "$reformatted" ]; then echo "format: reformatted $reformatted"; else echo "format: no changes"; fi
fi

# 3. Display for UI tests (Linux only).
: >"$work/norun"
if awk -F'\t' '$2 == "uiTest" { found = 1 } END { exit !found }' "$work/specs" && [ "$(uname -s)" = "Linux" ]; then
  if [ -z "$display_number" ]; then
    display_number="${DISPLAY:-}"; display_number="${display_number#:}"
    display_number="${display_number%%.*}"
    [[ "$display_number" =~ ^[0-9]+$ ]] || display_number=99
  fi
  dstatus=0
  dout="$("$root/.claude/scripts/setup-display.sh" "$display_number" 2>&1)" || dstatus=$?
  if [ "$dstatus" -eq 0 ]; then
    export DISPLAY=":$display_number"
  else
    echo "$dout"
    echo "display :$display_number could not be started; UI tests are not run"
    awk -F'\t' '$2 == "uiTest"' "$work/specs" >"$work/norun"
  fi
fi
awk -F'\t' '$2 != "uiTest" || !skipui' skipui="$([ -s "$work/norun" ] && echo 1 || echo 0)" "$work/specs" >"$work/runspecs"

# 4. One Gradle invocation.
tasks=()
while IFS=$'\t' read -r dir task; do
  [ -n "$dir" ] || continue
  gpath=":$(echo "$dir" | tr / :):$task"
  tasks+=("$gpath")
  if ! awk -F'\t' -v d="$dir" -v t="$task" '$1 == d && $2 == t && $3 == "*" { found = 1 } END { exit !found }' "$work/runspecs"; then
    # `Outer$*` covers the @Nested classes; a filter that matches nothing is harmless next to another that does.
    while IFS= read -r cls; do tasks+=(--tests "$cls" --tests "$cls\$*"); done < <(awk -F'\t' -v d="$dir" -v t="$task" '$1 == d && $2 == t { print $3 }' "$work/runspecs")
  fi
done < <(cut -f1,2 "$work/runspecs" | sort -u)

cmd=("${gradlew[@]}" testClasses ${tasks[@]+"${tasks[@]}"} --continue -q ${gradle_args[@]+"${gradle_args[@]}"})
echo "gradle: ${cmd[*]}"
status=0
touch "$marker"
"${cmd[@]}" >"$log" 2>&1 || status=$?

# 5. A uiTest that dies on the display service is cured by stopping the daemons. They are shared by every tree, so
# they are stopped only when no Gradle client runs anywhere (our own run has ended); else nothing is stopped or rerun,
# the digest prints as usual and the run ends FAILED with a message (`refusal`, printed with the verdict).
rerun=0
refusal=""
if grep -qF 'Build_gradle$DisplayService' "$log"; then
  if running="$("$root/.claude/scripts/gradle-running.sh" --any)"; then
    refusal="DisplayService cast error; another Gradle build runs (pid ${running%% *} in $(printf '%s\n' "$running" | head -n 1 | cut -d' ' -f2-)), so the daemons were not stopped: run again when it has finished"
  else
    rerun=1
    echo "DisplayService cast error: ./gradlew --stop, then rerun"
    ./gradlew --stop >/dev/null 2>&1 || true
    status=0
    touch "$marker"
    "${cmd[@]}" >"$log" 2>&1 || status=$?
  fi
fi

# 6. Digest.
digest_status=0
python3 -I - "$log" "$root" "$work/runspecs" "$work/norun" "$status" "$marker" "$work/incomplete" <<'PY' || digest_status=$?
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

log, root, specs, norun, status, marker, incomplete = sys.argv[1:8]
marker_time = os.path.getmtime(marker)
status = int(status)
text = open(log, errors="replace").read()
out = []


def rows(path):
    with open(path) as handle:
        return [line.rstrip("\n").split("\t") for line in handle if line.strip()]


def task_path(directory, task):
    return ":" + directory.replace("/", ":") + ":" + task


compile_errors = []
for line in text.splitlines():
    if line.startswith("e: "):
        line = line.replace("file://", "").replace(root + "/", "")
        if line not in compile_errors:
            compile_errors.append(line)
for line in compile_errors[:30]:
    out.append(line)
if len(compile_errors) > 30:
    out.append("... %d more compile errors (see log)" % (len(compile_errors) - 30))

failures = []
fresh_failure = False
unmatched = False
if not compile_errors:
    groups = {}
    for directory, task, cls in rows(specs):
        groups.setdefault((directory, task), []).append(cls)
    for (directory, task), classes in sorted(groups.items()):
        results = os.path.join(root, directory, "build", "test-results", task)
        if "*" in classes:
            files = sorted(glob.glob(os.path.join(results, "TEST-*.xml")))
            missing = []
        else:
            files = []
            missing = []
            for cls in classes:
                # The class itself and its @Nested classes (TEST-pkg.Outer$Inner.xml) count as its results.
                found = glob.glob(os.path.join(glob.escape(results), "TEST-%s.xml" % glob.escape(cls)))
                found += glob.glob(os.path.join(glob.escape(results), "TEST-%s$*.xml" % glob.escape(cls)))
                if found:
                    files.extend(sorted(found))
                else:
                    missing.append(cls.rsplit(".", 1)[-1])
        total = failed = skipped = 0
        for path in files:
            suite = ET.parse(path).getroot()
            # A failing task never counts as up to date, so a failure in a file older than this run is left over
            # from an earlier one (the task did not run, e.g. after a compile error).
            stale = os.path.getmtime(path) < marker_time
            if stale and int(suite.get("failures", 0)) + int(suite.get("errors", 0)) > 0:
                missing.append("%s (stale result)" % os.path.basename(path)[5:-4].rsplit(".", 1)[-1])
                continue
            total += int(suite.get("tests", 0))
            skipped += int(suite.get("skipped", 0))
            failed += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
            for case in suite.iter("testcase"):
                problem = case.find("failure")
                if problem is None:
                    problem = case.find("error")
                if problem is None:
                    continue
                body = problem.text or ""
                message = (problem.get("message") or body).strip()
                message = " ".join(message.splitlines()[0].split())[:200] if message else problem.get("type", "")
                simple = case.get("classname", "").rsplit(".", 1)[-1]
                entry = ["FAIL %s.%s: %s" % (simple, case.get("name"), message)]
                for frame in re.finditer(r"at (dev\.sluide[\w.$]*)\.([^.(]+)\(([^()]+)\)", body):
                    if len(entry) > 4:
                        break
                    owner = frame.group(1).rsplit(".", 1)[-1]
                    entry.append("  at %s (%s.%s)" % (frame.group(3), owner, frame.group(2)))
                failures.append(entry)
                fresh_failure = True
        summary = "%s  %d passed, %d failed, %d skipped" % (
            task_path(directory, task), total - failed - skipped, failed, skipped)
        if not files:
            summary = "%s  no test results" % task_path(directory, task)
        if missing:
            summary += "; no result for " + ", ".join(missing)
            unmatched = True
        out.append(summary)
seen = set()
for directory, task, cls in rows(norun):
    if (directory, task) not in seen:
        seen.add((directory, task))
        out.append("%s  NOT RUN (no display)" % task_path(directory, task))

budget = 40
shown = 0
for entry in failures:
    if shown + len(entry) > budget:
        out.append("... %d more failures (see log)" % (len(failures) - failures.index(entry)))
        break
    out.extend(entry)
    shown += len(entry)

if unmatched:
    open(incomplete, "w").close()

# Gradle's own reason, unless a compile error or a failing test explains the failure.
if status != 0 and not compile_errors and (unmatched or not fresh_failure):
    block = []
    capture = False
    for line in text.splitlines():
        if line.startswith("* What went wrong:"):
            capture = True
            continue
        if capture and re.match(r"\* (Try|Exception is|Get more help)", line):
            break
        if capture:
            block.append(line)
    while block and not block[-1].strip():
        block.pop()
    out.append("What went wrong:")
    out.extend(block[:25] or text.strip().splitlines()[-10:])

if out:
    print("\n".join(out))
PY

# check-tests.sh on the changed test files; only its findings, the totals line stays out when there are none.
changed_tests="$(changed_files | grep -E '/src/test/.*\.kt$' || true)"
if [ -n "$changed_tests" ]; then
  # shellcheck disable=SC2086
  findings="$(echo "$changed_tests" | tr '\n' '\0' | xargs -0 "$root/.claude/scripts/check-tests.sh" || true)"
  lines="$(printf '%s\n' "$findings" | wc -l | tr -d ' ')"
  if [ "$lines" -gt 1 ]; then
    echo "check-tests:"
    if [ "$lines" -gt 10 ]; then
      printf '%s\n' "$findings" | head -n 8
      echo "... $((lines - 9)) more findings dropped (run .claude/scripts/check-tests.sh on the files)"
      printf '%s\n' "$findings" | tail -n 1
    else
      printf '%s\n' "$findings"
    fi
  fi
fi

if [ -n "$refusal" ]; then
  echo "$refusal"
  echo "verify: FAILED (log: $log)"
  exit "$((status == 0 ? 1 : status))"
elif [ "$digest_status" -ne 0 ]; then
  echo "verify: FAILED (digest error; log: $log)"
  exit "$((status == 0 ? 1 : status))"
elif [ "$status" -eq 0 ] && [ -e "$work/incomplete" ]; then
  echo "verify: FAILED (a named test class has no result; log: $log)"
  exit 1
elif [ "$status" -eq 0 ] && [ -s "$work/norun" ]; then
  echo "verify: INCOMPLETE (UI tests NOT RUN, no display; log: $log)"
  exit 1
elif [ "$status" -eq 0 ] && [ "$no_tests" -eq 1 ]; then
  echo "verify: OK (compiled only, no tests run)"
elif [ "$status" -eq 0 ]; then
  if [ "$rerun" -eq 1 ]; then echo "(passed on the rerun)"; fi
  echo "verify: OK"
else
  echo "verify: FAILED (log: $log)"
fi
exit "$status"
