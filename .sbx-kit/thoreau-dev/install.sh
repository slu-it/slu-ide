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
  # Building the newest git from source (install_git below); git itself to fetch the sources
  git build-essential libcurl4-openssl-dev libssl-dev zlib1g-dev libexpat1-dev gettext
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

# The newest git 2.x release, from the official repository, built into /usr/local (ahead of /usr/bin in PATH). Git 3.0
# will need Rust (cargo), so 2.x only; NO_RUST leaves out the optional Rust parts of 2.x. A failure keeps the
# distribution's git and only warns: the sandbox works with it, apart from tests that expect a newer git.
git_repository=https://github.com/git/git
install_git() {
  local latest current workdir
  latest=$(git ls-remote --tags --refs "$git_repository" 'v2.*' |
    sed -n 's|.*refs/tags/v\([0-9]*\.[0-9]*\.[0-9]*\)$|\1|p' | sort -V | tail -n 1 || true)
  if [ -z "$latest" ]; then
    echo "Warning: could not read git's releases from $git_repository; keeping $(git --version)" >&2
    return 0
  fi
  current=$(/usr/local/bin/git --version 2>/dev/null | awk '{print $3}' || true)
  if [ "$current" = "$latest" ]; then
    echo "thoreau-dev: git $latest is installed"
    return 0
  fi
  workdir=$(mktemp -d)
  local options=(prefix=/usr/local NO_TCLTK=1 NO_RUST=1)
  if git -c advice.detachedHead=false clone -q --depth 1 --branch "v$latest" "$git_repository" "$workdir/git" &&
    make -C "$workdir/git" -j "$(nproc)" "${options[@]}" all >"$workdir/build.log" 2>&1 &&
    make -C "$workdir/git" "${options[@]}" install >>"$workdir/build.log" 2>&1; then
    echo "thoreau-dev: installed $(/usr/local/bin/git --version)"
    rm -rf "$workdir"
  else
    echo "Warning: building git $latest failed (log: $workdir/build.log); keeping $(git --version)" >&2
  fi
}
install_git
