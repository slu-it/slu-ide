#!/usr/bin/env bash
# Starts the real Thoreau app on a virtual display for a visual check, in a chosen theme, without touching the user's
# settings: the app runs with a throwaway user.home that holds only a settings.properties with the theme.
# Usage: .claude/scripts/run-app.sh [--theme dark|light] [--display N] [--setting key=value]... <project-folder>
#        .claude/scripts/run-app.sh --stop <pid>
# Defaults: theme dark, display 107 (outside the final gate's pool :99 to :106). --setting adds a line to the
# throwaway settings.properties (e.g. python.ty.path=/path/to/ty), repeatable. Builds the jar with Gradle when it is missing or older than the sources.
# Prints pid=, display=, home= (the throwaway folder) and log= (app output), one per line.
# Afterwards (see docs/testing.md):
#   DISPLAY=:107 import -window root shot.png      # screenshot
#   DISPLAY=:107 xdotool key ctrl+comma            # drive it with xdotool (clicks, keys, windowfocus)
# --stop kills the app, waits for it to end and deletes its throwaway home; Xvfb and openbox keep running.
set -euo pipefail

if [ "$(uname -s)" != "Linux" ]; then
  echo "Not needed on $(uname -s): start the app directly, it has a native display." >&2
  exit 0
fi

root="$(cd "$(dirname "$0")/../.." && pwd)"
state_dir="${TMPDIR:-/tmp}"

if [ "${1:-}" = "--stop" ]; then
  pid="${2:?usage: run-app.sh --stop <pid>}"
  state="$state_dir/thoreau-run-$pid.home"
  [ -f "$state" ] || { echo "No app started by run-app.sh with pid $pid" >&2; exit 1; }
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 40); do kill -0 "$pid" 2>/dev/null || break; sleep 0.25; done
  if kill -0 "$pid" 2>/dev/null; then kill -9 "$pid" 2>/dev/null || true; fi
  home="$(cat "$state")"
  case "$home" in */thoreau-run-*) rm -rf "$home" ;; esac
  rm -f "$state"
  echo "Stopped $pid"
  exit 0
fi

theme=dark
display_number=107
settings=()
while [ "$#" -gt 1 ]; do
  case "$1" in
    --theme) theme="${2:?}"; shift 2 ;;
    --display) display_number="${2:?}"; shift 2 ;;
    --setting) settings+=("${2:?}"); shift 2 ;;
    *) break ;;
  esac
done
case "$theme" in dark | light) ;; *) echo "Theme must be dark or light" >&2; exit 2 ;; esac
folder="$(cd "${1:?usage: run-app.sh [--theme dark|light] [--display N] [--setting key=value]... <project-folder>}" && pwd)"

jar="$root/ide/build/libs/ide-0.1.0-SNAPSHOT.jar"
stale() {
  [ -f "$jar" ] || return 0
  [ -n "$(find "$root/ide/src" "$root"/modules/*/src -type f -newer "$jar" -print -quit 2>/dev/null)" ] && return 0
  [ -n "$(find "$root"/*.gradle.kts "$root"/ide/*.gradle.kts "$root"/modules/*/*.gradle.kts -newer "$jar" -print -quit 2>/dev/null)" ] && return 0
  [ "$root/gradle/libs.versions.toml" -nt "$jar" ]
}
# Pid of a Gradle client (not a daemon) whose working directory is this tree, as in full-build.sh (reads /proc): `java
# -jar gradle/wrapper/gradle-wrapper.jar`, or the wrapper's main class with older wrappers. Prints "none" otherwise.
gradle_client_in_tree() {
  local tree pid
  tree="$(cd "$root" && pwd -P)"
  for pid in $(ps -eo pid=,args= | awk '$2 ~ /(^|\/)java$/ && /gradle-wrapper[.]jar|GradleWrapperMain/ { print $1 }'); do
    if [ "$(readlink "/proc/$pid/cwd" 2>/dev/null)" = "$tree" ]; then echo "$pid"; return 0; fi
  done
  echo none
}
if stale; then
  client="$(gradle_client_in_tree)"
  if [ "$client" != none ]; then
    echo "The jar must be built, but another Gradle build runs in $root (pid $client); wait for it to finish first." >&2
    exit 1
  fi
  echo "Building the jar" >&2
  gradle_args=(-q :ide:bootJar)
  case "$root" in */.claude/worktrees/*) gradle_args=(--no-daemon "${gradle_args[@]}") ;; esac
  (cd "$root" && ./gradlew "${gradle_args[@]}" >&2)
  touch "$jar" # Gradle keeps the old mtime when bootJar is up to date
fi

"$root/.claude/scripts/setup-display.sh" "$display_number" >&2
export DISPLAY=":${display_number}"

if ! command -v openbox >/dev/null 2>&1; then
  sudo -n env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openbox >&2 \
    || echo "Warning: openbox not installed; menus and dialogs will not work" >&2
fi
if command -v openbox >/dev/null 2>&1 && ! xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q 'window id'; then
  nohup openbox >/dev/null 2>&1 &
  sleep 1
fi

home="$(mktemp -d "$state_dir/thoreau-run-XXXXXX")"
mkdir -p "$home/.sluide"
echo "theme=${theme^^}" >"$home/.sluide/settings.properties"
for setting in ${settings[@]+"${settings[@]}"}; do echo "$setting" >>"$home/.sluide/settings.properties"; done
nohup java -Dprism.order=sw "-Duser.home=$home" -jar "$jar" "$folder" >"$home/app.log" 2>&1 &
pid=$!
echo "$home" >"$state_dir/thoreau-run-$pid.home"

shown=false
for _ in $(seq 1 240); do
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "The app died:" >&2
    tail -n 30 "$home/app.log" >&2
    rm -rf "$home" "$state_dir/thoreau-run-$pid.home"
    exit 1
  fi
  if xdotool search --onlyvisible --pid "$pid" --name 'Thoreau' >/dev/null 2>&1; then shown=true; break; fi
  sleep 0.25
done
if ! $shown; then
  echo "No window after 60 s:" >&2
  tail -n 30 "$home/app.log" >&2
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 40); do kill -0 "$pid" 2>/dev/null || break; sleep 0.25; done
  kill -9 "$pid" 2>/dev/null || true
  rm -rf "$home" "$state_dir/thoreau-run-$pid.home"
  exit 1
fi

echo "pid=$pid"
echo "display=:${display_number}"
echo "home=$home"
echo "log=$home/app.log"
