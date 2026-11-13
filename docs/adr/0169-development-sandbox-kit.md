# 0169. Development sandbox kit and `sbxenv.yaml`

- Status: accepted
- Date: 2026-11-13

## Context
Thoreau is developed in a Docker Sandbox (`sbx`) running Claude Code. Building, testing and driving the IDE there
needs more than the default agent image has: JDK 25 (the build's `jvmToolchain(25)` does not download one), Xvfb and
the GTK/X11 libraries for JavaFX on virtual displays, xdotool, ImageMagick and openbox for driving and screenshotting
the app, and the programs some features call (zsh, espeak-ng, `secret-tool`). In the sandbox used so far they were
installed by hand, or by `setup-display.sh` and `run-app.sh` with `sudo apt-get` on first use. A new sandbox had none
of them, and the default `claude` image moved from Ubuntu with a JDK to Debian 13 without one.

## Decision
- **A v3 mixin kit, `.sbx-kit/thoreau-dev/`,** on top of the default `claude` agent. Its `lifecycle@1` install hook
  runs `install.sh` as root once at sandbox creation and installs whatever of the list is missing (with recommended
  packages, as before). The script reaches the sandbox through the kit's content: `thoreau-dev.dockerfile` copies it
  to `/opt/thoreau-dev/`. A workload kit with the packages baked in at build time would start faster, but it would
  have to replace the `claude` agent kit that supplies Claude Code, its credentials and its network rules.
- **apt only:** JDK 25 is `openjdk-25-jdk-headless` from the distribution (Ubuntu 26.04, Debian 13), installed even
  when the default `java` is newer, since the toolchain needs exactly 25. The script waits for the agent kit's own
  background `apt-get update` instead of racing it for the lock. Images with older
  library names (no `t64` suffix) get the old names. A package apt cannot install, the JDK included, fails the install
  hook and with it the sandbox's creation: loud rather than a sandbox that half works.
- **Network:** the kit opens the Ubuntu, Debian, Docker and `dhi.io` apt hosts in the install phase only
  (`network-policy@2`); nothing extra at runtime.
- **Displays on demand:** the kit does not start Xvfb at boot. The scripts already start, and repair, displays and
  openbox when needed, and the full build uses a pool of eight.
- **`sbxenv.yaml` in the repository root** declares agent, workspace, the kit and resources (12 CPUs, 12 GB for the
  full build's eight displays). Docker suggests keeping it outside mounted folders, because the agent can edit it;
  it sits in the root anyway because Thoreau's Docker Sandbox menu offers the Environment session for a project
  with one there (ADR 0156). Changes to it and to `.sbx-kit/` are reviewed before a sandbox is created from them.
- The scripts keep their `apt-get` fallbacks for sandboxes created without the kit.

## Consequences
- `sbx env run` in the root (or Shortcut+Shift+B in Thoreau) gives a sandbox that can build, run UI tests and drive
  the real app without setup. Checked: `install.sh` on `docker.io/sbx/claude` (Debian 13), then `run-app.sh` showed
  the IDE on a virtual display.
- The install adds about two minutes to sandbox creation. A changed kit applies to new sandboxes only.
- Not covered: Claude's memory and plans, Gradle caches, `~/.sluide` models and pulled Docker images.
- See [.sbx-kit/README.md](../../.sbx-kit/README.md).
