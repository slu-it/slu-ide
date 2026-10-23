#!/usr/bin/env bash
# The final gate: the full build with UI tests on displays that answer, then the docs index check.
# On Linux it first runs setup-display.sh for a pool of virtual displays (:99 upwards, SLUIDE_UI_DISPLAYS of them,
# default 6), which repairs left-over or hung ones, so UI tests never fail with "Unable to open DISPLAY" because of a
# stale one. Gradle hands each module's uiTest a display of its own, so that many modules test in parallel (ADR 0081).
# It refuses to start while another Gradle build runs in this working tree (both would share build/, and classes would
# vanish under the running tests).
# Usage: .claude/scripts/full-build.sh [extra Gradle arguments]; exits with the build's status.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd -P)"
cd "$root"

# Gradle clients (not daemons) whose working directory is this tree (read from /proc, so Linux only): `java -jar gradle/wrapper/gradle-wrapper.jar`, or
# the wrapper's main class with older wrappers.
for pid in $(ps -eo pid=,args= | awk '$2 ~ /(^|\/)java$/ && /gradle-wrapper[.]jar|GradleWrapperMain/ { print $1 }'); do
  if [ "$(readlink "/proc/$pid/cwd" 2>/dev/null)" = "$root" ]; then
    echo "Another Gradle build runs in $root (pid $pid); wait for it to finish first." >&2
    exit 1
  fi
done

if [ "$(uname -s)" = "Linux" ]; then
  count="${SLUIDE_UI_DISPLAYS:-6}"
  [ "$count" -ge 1 ] || { echo "SLUIDE_UI_DISPLAYS must be at least 1, not $count" >&2; exit 1; }
  displays=()
  for number in $(seq 99 $((98 + count))); do
    # Only what was installed or repaired; set -e still ends the build when a display cannot be started.
    out="$("$root/.claude/scripts/setup-display.sh" "$number")"
    grep -vE '^(Xvfb already running|export DISPLAY)' <<<"$out" || true
    displays+=(":$number")
  done
  export DISPLAY=:99
  set -- "-Psluide.uiDisplays=$(IFS=,; echo "${displays[*]}")" "$@"
fi

status=0
./gradlew build -q "$@" || status=$?
"$root/.claude/scripts/check-docs-index.sh" || status=$((status == 0 ? 1 : status))
exit "$status"
