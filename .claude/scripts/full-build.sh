#!/usr/bin/env bash
# The final gate: the full build with UI tests on displays that answer, then the docs index check,
# the unused imports check and a report of the test code that breaks docs/testing.md.
# On Linux it first runs setup-display.sh for a pool of virtual displays (:99 upwards, SLUIDE_UI_DISPLAYS of them,
# default 8), which repairs left-over or hung ones, so UI tests never fail with "Unable to open DISPLAY" because of a
# stale one. Gradle hands each module's uiTest a display of its own, so that many modules test in parallel (ADR 0081).
# It refuses to start while another Gradle build runs in this working tree or a folder below it, not counting linked
# worktrees (both would share build/, and classes would vanish under the running tests).
# The build needs the memory: it refuses while a break-check run with its own daemon is alive, and kills the private
# daemons that killed runs left behind (break-check.sh, "sluide-private-daemon-<pid>-" in their arguments).
# Usage: .claude/scripts/full-build.sh [extra Gradle arguments]; exits with the build's status, or 1 when only a check
# failed.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd -P)"
cd "$root"

# Gradle clients (not daemons) in this tree or below it, found by gradle-running.sh (reads /proc, so Linux only).
if found="$("$root/.claude/scripts/gradle-running.sh" "$root")"; then
  echo "Another Gradle build runs in $root (pid ${found%% *}); wait for it to finish first." >&2
  exit 1
fi

# Private break-check daemons: the owning run's pid is the number after the tag.
while read -r pid owner; do
  [ -n "$pid" ] || continue
  if kill -0 "$owner" 2>/dev/null; then
    echo "A break-check run (pid $owner) is still running with its own Gradle daemon; wait for it or stop it first." >&2
    exit 1
  fi
  kill "$pid" 2>/dev/null || true
  echo "stopped a left-over break-check daemon (pid $pid)"
done < <(ps -eo pid=,args= | sed -nE 's/^ *([0-9]+) .*sluide-private-daemon-([0-9]+)-.*/\1 \2/p')

if [ "$(uname -s)" = "Linux" ]; then
  count="${SLUIDE_UI_DISPLAYS:-8}"
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
"$root/.claude/scripts/check-unused-imports.sh" || status=$((status == 0 ? 1 : status))
# Report only: the totals line of check-tests.sh, the findings are listed by running it alone.
"$root/.claude/scripts/check-tests.sh" | tail -n 1 || true
# Report only: production files over the size limit of ADR 0054 (a round once pushed MainWindow to 601 lines).
"$root/.claude/scripts/check-file-sizes.sh" || true
exit "$status"
