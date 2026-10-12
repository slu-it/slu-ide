#!/usr/bin/env bash
# Prepares a headless Linux sandbox (Ubuntu 24.04+) so the JavaFX UI can be started and screenshotted:
# installs Xvfb, the GTK/X11 libraries JavaFX loads, and screenshot/automation tools, then starts a virtual display.
# Safe to run repeatedly. Usage: .claude/scripts/setup-display.sh [display-number, default 99]
#
# Afterwards:
#   export DISPLAY=:99
#   java -Dprism.order=sw -jar ide/build/libs/ide-*.jar &
#   import -window root shot.png        # screenshot; xdotool for clicks and keys
# Without a window manager dialogs get no keyboard focus: focus them via `xdotool windowfocus <id>`.
set -euo pipefail

display_number="${1:-99}"
packages=(xvfb libgtk-3-0t64 libx11-6 libxtst6 libxxf86vm1 libgl1 libasound2t64 xdotool imagemagick x11-apps)

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

if [ -S "/tmp/.X11-unix/X${display_number}" ]; then
  echo "Xvfb already running on :${display_number}"
else
  nohup Xvfb ":${display_number}" -screen 0 1280x800x24 >/dev/null 2>&1 &
  for _ in $(seq 1 20); do
    [ -S "/tmp/.X11-unix/X${display_number}" ] && break
    sleep 0.25
  done
  [ -S "/tmp/.X11-unix/X${display_number}" ] || { echo "Xvfb failed to start" >&2; exit 1; }
  echo "Started Xvfb on :${display_number}"
fi

echo "export DISPLAY=:${display_number}"
