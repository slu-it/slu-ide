#!/usr/bin/env bash
# Prepares a headless Linux sandbox (Ubuntu 24.04+) so the JavaFX UI can be started and screenshotted:
# installs Xvfb, the GTK/X11 libraries JavaFX loads, and screenshot/automation tools, then starts a virtual display.
# Safe to run repeatedly: a display that exists but does not answer (Xvfb gone, its socket and lock file left over, or
# hung) is stopped, cleaned up and started again. Usage: .claude/scripts/setup-display.sh [display-number, default 99]
#
# Afterwards:
#   export DISPLAY=:99
#   java -Dprism.order=sw -jar ide/build/libs/ide-*.jar &
#   import -window root shot.png        # screenshot; xdotool for clicks and keys
# Without a window manager dialogs get no keyboard focus: focus them via `xdotool windowfocus <id>`.
set -euo pipefail

display_number="${1:-99}"
packages=(xvfb libgtk-3-0t64 libx11-6 libxtst6 libxxf86vm1 libgl1 libasound2t64 xdotool imagemagick x11-apps x11-utils)

if [ "$(uname -s)" != "Linux" ]; then
  echo "Not needed on $(uname -s): it has a native display." >&2
  exit 0
fi

missing=()
for package in "${packages[@]}"; do
  dpkg -s "$package" >/dev/null 2>&1 || missing+=("$package")
done

if [ "${#missing[@]}" -gt 0 ]; then
  echo "Installing: ${missing[*]}"
  sudo -n env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${missing[@]}"
fi

socket="/tmp/.X11-unix/X${display_number}"
lock="/tmp/.X${display_number}-lock"

# The socket alone proves nothing: it outlives a killed Xvfb. Only a display that answers counts as running; the
# timeout because a hung (e.g. stopped) Xvfb accepts the connection and then never replies.
answers() { timeout 3 xdpyinfo -display ":${display_number}" >/dev/null 2>&1; }

if answers; then
  echo "Xvfb already running on :${display_number}"
else
  if [ -e "$socket" ] || [ -e "$lock" ]; then
    echo "Display :${display_number} does not answer; cleaning it up"
    # A hung Xvfb on this display; ps + awk instead of pkill -f, which can match the calling shell. SIGKILL after a
    # grace period: a stopped process never acts on SIGTERM.
    pids="$(ps -eo pid=,args= | awk -v d=":${display_number}" '$2 ~ /(^|\/)Xvfb$/ && $3 == d { print $1 }')"
    for pid in $pids; do kill "$pid" 2>/dev/null || true; done
    sleep 0.5
    for pid in $pids; do kill -9 "$pid" 2>/dev/null || true; done
    rm -f "$socket" "$lock"
  fi
  nohup Xvfb ":${display_number}" -screen 0 1280x800x24 >/dev/null 2>&1 &
  for _ in $(seq 1 40); do
    answers && break
    sleep 0.25
  done
  answers || { echo "Xvfb failed to start on :${display_number}" >&2; exit 1; }
  echo "Started Xvfb on :${display_number}"
fi

echo "export DISPLAY=:${display_number}"
