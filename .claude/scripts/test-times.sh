#!/usr/bin/env bash
# Shows where the test time goes, from the JUnit XML reports of the last Gradle runs (*/build/test-results/<task>/):
# the summed suite time and test count per module and task (test, uiTest, uiTestPart1, uiTestPart2), then the 15
# slowest test classes as `seconds tests module task class`. Suites run in parallel or as two parts, so the sums
# are CPU-style totals, not the wall-clock time of the build.
# Stale results are skipped (and named on stderr): a task folder whose newest report is more than 10 minutes older
# than the module's newest one, and uiTest when uiTestPart1/2 results of the module are at least as new.
# Usage: .claude/scripts/test-times.sh [module-path...]   (e.g. modules/previews ide; default: all modules)
set -euo pipefail
cd "$(dirname "$0")/../.."

if [ "$#" -eq 0 ]; then
  set -- ide modules/*
fi

# Modification time in seconds of the newest XML report in a folder (0 if none).
newest() {
  local best=0 time report
  for report in "$1"TEST-*.xml; do
    [ -f "$report" ] || continue
    time="$(stat -c %Y "$report" 2>/dev/null || stat -f %m "$report")"
    [ "$time" -le "$best" ] || best="$time"
  done
  echo "$best"
}

reports=()
for module in "$@"; do
  module="${module%/}"
  dirs=()
  times=()
  moduleNewest=0
  for dir in "$module"/build/test-results/*/; do
    [ -d "$dir" ] || continue
    time="$(newest "$dir")"
    [ "$time" -gt 0 ] || continue
    dirs+=("$dir")
    times+=("$time")
    [ "$time" -le "$moduleNewest" ] || moduleNewest="$time"
  done
  [ "${#dirs[@]}" -gt 0 ] || continue
  partsNewest=0
  for i in "${!dirs[@]}"; do
    case "${dirs[$i]}" in
      */uiTestPart[0-9]*/) [ "${times[$i]}" -le "$partsNewest" ] || partsNewest="${times[$i]}" ;;
    esac
  done
  for i in "${!dirs[@]}"; do
    dir="${dirs[$i]}"
    if [ $((moduleNewest - times[i])) -gt 600 ]; then
      echo "Skipping stale ${dir%/}" >&2
      continue
    fi
    case "$dir" in
      */uiTest/)
        if [ "$partsNewest" -ge "${times[$i]}" ]; then
          echo "Skipping ${dir%/}: uiTestPart results are newer" >&2
          continue
        fi
        ;;
    esac
    for report in "$dir"TEST-*.xml; do
      [ -f "$report" ] && reports+=("$report")
    done
  done
done
if [ "${#reports[@]}" -eq 0 ]; then
  echo "No test reports found; run the tests first." >&2
  exit 0
fi

# The first <testsuite ...> element of each report carries the class name, test count and time.
grep -H -m1 '<testsuite ' "${reports[@]}" | awk '
function attribute(text, name,   value) {
  if (!match(text, " " name "=\"[^\"]*\"")) return ""
  value = substr(text, RSTART + length(name) + 3, RLENGTH - length(name) - 4)
  return value
}
{
  path = $0; sub(/:.*/, "", path)
  split(path, segments, "/")
  # <module path>/build/test-results/<task>/TEST-<class>.xml
  task = segments[length(segments) - 1]
  module = path; sub(/\/build\/test-results\/.*/, "", module)
  seconds = attribute($0, "time") + 0
  tests = attribute($0, "tests") + 0
  class = attribute($0, "name")
  key = module " " task
  sumSeconds[key] += seconds; sumTests[key] += tests
  rows++; rowText[rows] = sprintf("%8.2f %6d  %s %s %s", seconds, tests, module, task, class); rowSeconds[rows] = seconds
}
END {
  print "Per module and task (seconds, tests):"
  for (key in sumSeconds) printf "%8.1f %6d  %s\n", sumSeconds[key], sumTests[key], key | "sort -k3,3 -k4,4"
  close("sort -k3,3 -k4,4")
  print ""
  print "Slowest classes (seconds, tests, module, task, class):"
  for (shown = 0; shown < 15 && shown < rows; shown++) {
    best = 0
    for (i = 1; i <= rows; i++) if (!(i in used) && (best == 0 || rowSeconds[i] > rowSeconds[best])) best = i
    used[best] = 1
    print rowText[best]
  }
}'
