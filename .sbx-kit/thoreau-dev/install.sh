#!/usr/bin/env bash
# Installs what Thoreau's development needs on top of the default Claude Code sandbox: JDK 25 (the build's
# jvmToolchain(25) does not download one), the packages the scripts in .claude/scripts otherwise install on demand
# (virtual displays, window manager, screenshot and automation tools), and the programs some features and tests call.
# Runs as root, once per sandbox, as the kit's install hook (thoreau-dev.yaml). Safe to run again: installs only what
# is missing. Tested on Ubuntu 26.04 and on the default image docker.io/sbx/claude (Debian 13).
# Usage (by hand): sudo .sbx-kit/thoreau-dev/install.sh
set -euo pipefail

packages=(
  # JavaFX on a virtual display (setup-display.sh)
  xvfb libgtk-3-0t64 libx11-6 libxtst6 libxxf86vm1 libgl1 libasound2t64
  # Screenshots, UI automation, display checks (xdpyinfo, xprop, xev)
  imagemagick xdotool x11-apps x11-utils
  # Window manager, so menus and dialogs get focus (run-app.sh)
  openbox
  # rsvg-convert for checking SVGs
  librsvg2-bin
  # Terminal tests with a real zsh
  zsh
  # Read Aloud (speech module)
  espeak-ng
  # Saved secrets on Linux (secrets module: secret-tool and a Secret Service)
  libsecret-tools gnome-keyring dbus
  # npm for the real-tsc tests (SLUIDE_TEST_TSC)
  nodejs npm
)

if [ "$(id -u)" -ne 0 ]; then
  echo "install.sh must run as root" >&2
  exit 1
fi
export DEBIAN_FRONTEND=noninteractive

# A failing extra repository must not stop the install from the others.
update() { apt-get update -qq -o DPkg::Lock::Timeout=300 || echo "Warning: apt-get update reported errors; installing from what it could fetch" >&2; }
available() { [ -n "$(apt-cache policy "$1" 2>/dev/null | awk '/Candidate:/ && $2 != "(none)"')" ]; }
installed() { dpkg -s "$1" >/dev/null 2>&1; }
# The build's jvmToolchain(25) needs a JDK 25 (javac), whatever the default java is.
has_jdk25() {
  local javac
  for javac in /usr/lib/jvm/*/bin/javac; do
    [ -x "$javac" ] && "$javac" -version 2>&1 | grep -q '^javac 25' && return 0
  done
  return 1
}

# The agent kit's own startup hook may run apt-get at the same time; wait for it (at most 5 minutes) instead of
# failing on the lock or reading half-written lists.
for _ in $(seq 1 150); do pgrep -x apt-get >/dev/null 2>&1 || break; sleep 2; done
update
available bash || { echo "apt-get update fetched no package lists; check the network policy for the apt mirrors" >&2; exit 1; }

# Images older than Ubuntu 24.04 / Debian trixie have these libraries without the t64 suffix.
resolved=()
for package in "${packages[@]}"; do
  if [[ "$package" == *t64 ]] && ! installed "$package" && ! available "$package"; then
    package="${package%t64}"
  fi
  resolved+=("$package")
done

if available openjdk-25-jdk-headless; then
  resolved+=(openjdk-25-jdk-headless)
elif ! has_jdk25; then
  echo "JDK 25 is missing and openjdk-25-jdk-headless is not available from apt" >&2
  exit 1
fi

missing=()
for package in "${resolved[@]}"; do
  installed "$package" || missing+=("$package")
done

if [ "${#missing[@]}" -eq 0 ]; then
  echo "thoreau-dev: nothing to install"
else
  # With recommended packages, as in the sandbox this list was taken from (fonts, for one, come that way).
  apt-get install -y -qq -o DPkg::Lock::Timeout=300 "${missing[@]}"
  echo "thoreau-dev: installed ${missing[*]}"
fi
