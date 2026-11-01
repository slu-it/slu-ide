# 0117. Docker through its CLI and a window `DockerSession`

- Status: accepted; amends [0087](0087-commands-in-an-interactive-login-shell.md)
- Date: 2026-11-01

## Context
Thoreau gets a Docker tab (containers, images, volumes, networks), and later Compose and Dockerfile views. Docker
Desktop, Colima, OrbStack, Rancher Desktop and Podman must all work. They differ in where the engine listens (a socket
path, `DOCKER_HOST`, a Docker context, a Podman connection), and each user's terminal is already set up to reach
theirs. Talking to the engine's API directly (a socket client, docker-java) would mean finding that socket again,
handling contexts, TLS and SSH ourselves, and Podman's API would still differ.

The tab lists often: on opening, after every event, and on F5. Commands of the IDE run in an interactive login shell
([ADR 0087](0087-commands-in-an-interactive-login-shell.md)), which costs one shell start each time (often hundreds of
milliseconds with a large `.zshrc`) and mixes the startup files' output into the command's.

## Decision
- **The CLI with JSON output is the only way to the engine.** `ps -a`, `images`, `volume ls`, `network ls`,
  `version`, `events`, `stats` with `--format json`, `inspect` as it is, `logs -f --timestamps`. Docker 23 and later
  print one object per line, Podman a (pretty-printed) array; a splitter cuts both, and the terminal escapes `docker
  stats` writes, into JSON values. Pure parsers normalise both into one model (`dev.sluide.docker.model`): Podman's
  `Names` array, port objects, Unix-second `Created`, lowercase `State` words, events with `Status`/`Name`, lowercase
  network keys. Podman fixtures are hand-written from its documented shapes, Docker's are captured.
- **The executable is a setting** (Settings → Docker, `docker.executable`, default `docker`; `podman` or a full path).
- **Resolved once per window through the login shell, then run directly.** One script in the user's interactive login
  shell (`loginShellPrints`, the same pseudo terminal as `loginShellFinds`) prints `command -v <name>` and the
  environment; the CLI then runs with `ProcessBuilder`, without a shell, with the IDE's environment overlaid by the
  shell's `PATH`, `DOCKER_HOST`, `DOCKER_CONTEXT`, `DOCKER_CONFIG`, `DOCKER_CERT_PATH`, `DOCKER_TLS_VERIFY`,
  `CONTAINER_HOST`, `CONTAINER_CONNECTION` and `XDG_RUNTIME_DIR`: the shell's non-empty values, and those it does not
  set are removed (an inherited, stale `DOCKER_HOST` must not win). An alias or function (zsh's `command -v` prints
  those too) counts as not found, with a message asking for the full path; other output (csh's error) as not found.
  If the shell does not answer, a name is looked up on the IDE's own `PATH`. ADR 0087 still holds for what the user
  runs and sees (Compose commands, builds): those go through `CommandRunner` in an embedded terminal. Of ADR 0087's
  rejected option (capturing the environment once) only the variables above are taken, and only for the frequent
  background calls.
- **`DockerSession`, a window bean**, holds the resolved CLI, the engine state (`Idle`, `Checking`, `Available`,
  `Unavailable`, `NotFound`) and observable lists of the four kinds. One shared `docker events` stream runs while
  anything subscribes (the tab, later Compose views). Events are routed to the lists they change (`reloadsFor`; exec
  sessions of health checks change nothing) and coalesced: the first event starts a 200 ms wait, then every list
  marked meanwhile reloads once. When the stream ends or a load fails, the state turns `Unavailable` and the session
  retries after 2 s, doubling up to 30 s, while subscribed, one retry pending at a time; the stream starts before the
  lists load, so nothing in between is missed; when the engine answers again, everything reloads. The last
  subscription closing stops the stream and the retries. A changed setting connects again at once while subscribed.
  Views run actions through `submit` and open log and stats streams through `openStream`, each on a thread of its own
  (a failure to open reaches the view on the FX thread); the session's own work runs on one `NamedThreads` thread,
  nothing blocks the FX thread. Closing a stream kills the CLI, and no callback starts afterwards; a pull stops when its
  thread is interrupted.
- **Commands are logged** like git's (ADR 0060): a process-wide number, start and end; listings at DEBUG (their
  failures at INFO, as they repeat while the engine is down), actions at INFO (failures at WARNING with the CLI's
  error).
- **No remote-context switching.** Whatever context or connection is current in the user's shell is used; the tab only
  names the current Docker context.
- Details that follow from the CLIs: dangling images come from a second listing (`--filter dangling=true`), because
  Docker 29 with the containerd store leaves them out of the plain one and `--all` would add the classic store's
  intermediate layers; whether a volume is in use comes from `volume ls --filter dangling=true`, because Podman's `ps`
  lists only mount targets; `volume prune` gets `--all` for Docker (23 and later prune only anonymous volumes without
  it), not for Podman.

## Consequences
- Every engine the user's terminal reaches works without configuration in Thoreau, Podman included; the price is one
  process per listing and the CLI's JSON as the contract, which Docker and Podman keep stable but not identical.
- A changed `.zshrc` (another `DOCKER_HOST`) counts for the Docker tab only after a Refresh with another setting or in
  a new window; Compose and build runs see it at once.
- Docker before 23 prints no JSON for `--format json` and is not supported.
- Amends [ADR 0087](0087-commands-in-an-interactive-login-shell.md): its rule (a login shell per command) stays for
  what the user runs; the Docker tab's frequent background calls capture the shell's environment once instead.
- New module `docker` in the editor family; see [ADR 0010](0010-docker-sandbox.md) (`sbx`, separate),
  [ADR 0070](0070-window-services.md) (the session becomes a window service for the Compose and Dockerfile views) and
  [ADR 0087](0087-commands-in-an-interactive-login-shell.md). Listed in [../index.md](../index.md).
