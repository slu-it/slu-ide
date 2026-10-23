#!/usr/bin/env bash
# The final gate: the full build with UI tests on a display that answers, then the docs index check.
# On Linux it first runs setup-display.sh, which repairs a left-over or hung virtual display, so UI tests never fail
# with "Unable to open DISPLAY" because of a stale one. It refuses to start while another Gradle build runs in this
# working tree (both would share build/, and classes would vanish under the running tests).
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
  "$root/.claude/scripts/setup-display.sh" 99
  export DISPLAY=:99
fi

status=0
./gradlew build -q "$@" || status=$?
"$root/.claude/scripts/check-docs-index.sh" || status=$((status == 0 ? 1 : status))
exit "$status"
