#!/usr/bin/env bash
# Prepares a headless Linux sandbox (Ubuntu 24.04+) so the JavaFX UI can be started and screenshotted:
# installs Xvfb, the GTK/X11 libraries JavaFX loads, and screenshot/automation tools, then starts a virtual display.
# Safe to run repeatedly: a display that exists but does not answer (Xvfb gone, its socket and lock file left over, or
# stopped) is stopped, cleaned up and started again. An Xvfb process that is not stopped may just be slow under load:
# its display gets about 15 seconds to answer before it counts as hung.
# Usage: .claude/scripts/setup-display.sh [display-number, default 99]
#
# Afterwards:
#   export DISPLAY=:99
#   .claude/scripts/run-app.sh <project-folder>   # starts the real app (builds the jar if needed)
#   import -window root shot.png        # screenshot; xdotool for clicks and keys
# Also creates an empty ~/.zshrc if missing (zsh's new-user menu would hang TerminalUiTest's real-shell test).
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

# Pids of the Xvfb processes of this display (ps + awk instead of pkill -f, which can match the calling shell), with
# "running" only those whose state is not stopped (T).
xvfb_pids() {
  ps -eo pid=,stat=,args= | awk -v d=":${display_number}" -v only="${1:-all}" \
    '$3 ~ /(^|\/)Xvfb$/ && $4 == d && (only == "all" || $2 !~ /^T/) { print $1 }'
}

# A live (not stopped) Xvfb that does not answer yet may only be slow under load (the final gate runs UI tests on 8
# displays): retry for about 15 seconds before it is treated as hung. A stopped process never answers: no waiting.
ok=false
answers && ok=true
if ! $ok && [ -n "$(xvfb_pids running)" ]; then
  deadline=$((SECONDS + 15))
  while [ "$SECONDS" -lt "$deadline" ] && [ -n "$(xvfb_pids running)" ]; do
    if answers; then ok=true; break; fi
    sleep 0.5
  done
fi

if $ok; then
  echo "Xvfb already running on :${display_number}"
else
  if [ -e "$socket" ] || [ -e "$lock" ]; then
    echo "Display :${display_number} does not answer; cleaning it up"
    # A hung Xvfb on this display. SIGKILL after a grace period: a stopped process never acts on SIGTERM.
    pids="$(xvfb_pids)"
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

# TerminalUiTest's real-shell test hangs on zsh's new-user menu without a ~/.zshrc.
[ -e "$HOME/.zshrc" ] || touch "$HOME/.zshrc"

echo "export DISPLAY=:${display_number}"
