#!/usr/bin/env bash
# jvmPath wrapper of the pitest UI runs (-Psluide.pitest.ui=true, root build.gradle.kts): pitest starts one JVM per
# worker (and one for the coverage run) and calls this script in place of `java`. Each of them gets the first free
# display of the pool, so TestFX tests of parallel workers do not take each other's window focus (ADR 0081).
# The lease is an flock on <root>/build/pitest/display-<N>.lock; the lock's file descriptor survives the exec, so the
# display is leased for the life of the JVM and free again when it dies. All displays leased: waits, polling every 0.2 s.
# The displays must already run (verify.sh --display N or setup-display.sh N start and repair one); this script
# starts none.
#
# Env, set by the pitest task in build.gradle.kts:
#   SLUIDE_PITEST_DISPLAYS  comma list of display numbers, e.g. 111,113
#   SLUIDE_PITEST_JAVA      the java of the Gradle toolchain, which then runs the worker
#   SLUIDE_PITEST_LOCKS     folder for the lock files and leases.log (the root project's build/pitest), shared by
#                           the modules of one Gradle call, never by trees
# Usage: pitest-java.sh <java arguments...>
set -euo pipefail

displays="${SLUIDE_PITEST_DISPLAYS:-}"
java="${SLUIDE_PITEST_JAVA:-}"
locks="${SLUIDE_PITEST_LOCKS:-}"
if [ -z "$displays" ] || [ -z "$java" ] || [ -z "$locks" ]; then
  echo "pitest-java.sh: SLUIDE_PITEST_DISPLAYS, SLUIDE_PITEST_JAVA and SLUIDE_PITEST_LOCKS must be set" >&2
  exit 2
fi
if ! command -v flock >/dev/null 2>&1; then
  echo "pitest-java.sh: needs flock (util-linux)" >&2
  exit 2
fi
mkdir -p "$locks"

while true; do
  for display in ${displays//,/ }; do
    exec 9>"$locks/display-$display.lock" || { echo "pitest-java.sh: cannot open a lock file in $locks" >&2; exit 2; }
    if flock -n 9; then
      export DISPLAY=":$display"
      echo "$(date +%T) pid=$$ display=:$display" >> "$locks/leases.log"
      exec "$java" "$@"
    fi
    exec 9>&-
  done
  sleep 0.2
done
