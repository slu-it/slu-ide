#!/usr/bin/env bash
# Finds running Gradle clients (the wrapper processes, not the daemons): processes whose command is `java` with
# gradle-wrapper.jar or GradleWrapperMain (older wrappers) in the arguments. Their working directory comes from
# /proc/<pid>/cwd, so on a system without /proc (macOS) nothing is found and the exit status is 1.
# Usage: .claude/scripts/gradle-running.sh <dir>   # clients whose working directory is <dir> or below it
#        .claude/scripts/gradle-running.sh --any   # every client on the machine
# <dir> is resolved physically (pwd -P); "below" means the working directory equals <dir> or starts with <dir>/.
# Clients inside <dir>/.claude/worktrees (or that folder itself) are not counted for <dir>: a linked worktree is another
# tree, ask for it by its own path. --any lists everything.
# Prints one line "<pid> <working directory>" per client found. Never reports this script or its caller.
# Exit codes: 0 at least one client found, 1 none, 2 usage error.
set -euo pipefail

usage="Usage: gradle-running.sh <dir> | --any"
[ $# -eq 1 ] || { echo "$usage" >&2; exit 2; }
dir=""
if [ "$1" != "--any" ]; then
  dir="$(cd "$1" 2>/dev/null && pwd -P)" || { echo "Not a directory: $1" >&2; exit 2; }
fi

prefix="${dir%/}" # "" for /, so that "$prefix/*" is /*
found=1
for pid in $(ps -eo pid=,args= | awk '$2 ~ /(^|\/)java$/ && /gradle-wrapper[.]jar|GradleWrapperMain/ { print $1 }'); do
  if [ "$pid" = "$$" ] || [ "$pid" = "$PPID" ]; then continue; fi
  cwd="$(readlink "/proc/$pid/cwd" 2>/dev/null)" || continue
  [ -n "$cwd" ] || continue
  if [ -z "$dir" ]; then
    echo "$pid $cwd"
    found=0
  elif { [ "$cwd" = "$dir" ] || [[ "$cwd" == "$prefix"/* ]]; } \
    && [ "$cwd" != "$prefix/.claude/worktrees" ] && [[ "$cwd" != "$prefix/.claude/worktrees/"* ]]; then
    echo "$pid $cwd"
    found=0
  fi
done
exit "$found"
