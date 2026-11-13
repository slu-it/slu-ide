# Sandbox kits

Kits for developing Thoreau in a Docker Sandbox (`sbx`, v0.45 or later for v3 kits). `sbxenv.yaml` in the repository
root starts Claude Code with them ([ADR 0169](../docs/adr/0169-development-sandbox-kit.md)).

```bash
sbx env plan     # in the repository root: shows agent, workspace, kits and options
sbx env run      # creates the sandbox on first use and starts Claude Code in it
```

Thoreau's **Docker Sandbox → New Docker Sandbox Session (Environment)** (Shortcut+Shift+B) runs `sbx env run` too.

## thoreau-dev

A mixin on top of the default `claude` agent (today Debian 13; Ubuntu works too). Its install hook runs
`install.sh` as root once, when the sandbox is created (log: `/var/log/sbx-kits/thoreau-dev/install-*.log`), and
installs what is missing:

| Packages | For |
|---|---|
| openjdk-25-jdk-headless | The build's `jvmToolchain(25)`, which does not download a JDK |
| xvfb, libgtk-3-0t64, libx11-6, libxtst6, libxxf86vm1, libgl1, libasound2t64 | JavaFX on virtual displays (`setup-display.sh`, UI tests) |
| imagemagick, xdotool, x11-apps, x11-utils | Screenshots (`import`), driving the UI, display checks (`xdpyinfo`, `xprop`, `xev`) |
| openbox | A window manager, so menus and dialogs get focus (`run-app.sh`) |
| librsvg2-bin | `rsvg-convert` for checking SVGs |
| zsh | Terminal tests with a real zsh |
| espeak-ng | Read Aloud |
| libsecret-tools, gnome-keyring, dbus | Saved secrets on Linux (`secret-tool`) |
| nodejs, npm | The real-tsc tests (`SLUIDE_TEST_TSC`) |

It also writes an empty `~/.zshrc` (zsh's new-user menu would hang the terminal tests). Displays are not started at
boot: `.claude/scripts/setup-display.sh` and `run-app.sh` start Xvfb and openbox when needed and repair hung ones.
The kit's network policy opens the apt mirrors only while the install hook runs.

Files: `thoreau-dev.yaml` (descriptor), `thoreau-dev.dockerfile` (puts `install.sh` at `/opt/thoreau-dev/`),
`install.sh`.

## Changing the kit
- Add a package to the array in `install.sh`, with a comment saying what uses it. Running
  `sudo .sbx-kit/thoreau-dev/install.sh` in an existing sandbox installs it there too.
- Kits apply when a sandbox is created: `sbx kit add` cannot add a v3 mixin to an existing sandbox. To get a changed
  kit, remove the sandbox and run `sbx env run` again.
- The agent can edit this folder and `sbxenv.yaml`. Read their diff before creating a sandbox from them.

## Claude's history: claude-sessions
`sbxenv.yaml` also adds Docker's `docker.io/sbx/claude-sessions` kit. It mounts a host folder at
`~/claude-history` and links `~/.claude/projects` (session transcripts for `--resume`, and the memory), `sessions`,
`todos`, `shell-snapshots` and `statsig` into `~/claude-history/<sandbox name>/`. Removing and recreating the
sandbox keeps them, as long as its `name` in `sbxenv.yaml` stays the same. Needs sbx 0.50 or later (`host-mount@1`).

Not kept: `~/.claude/plans`, `~/.claude.json` and other user settings, the Gradle caches, `~/.sluide` (models,
settings) and pulled Docker images. A new sandbox starts without them.
