# 0120. Dockerfile Build view, Run dialog and values outside the command line

- Status: accepted; amends [0029](0029-file-name-and-shebang-matching.md) and [0119](0119-compose-files.md)
- Date: 2026-11-01

## Context
After the Docker tab ([ADR 0117](0117-docker-through-the-cli.md)) and compose files
([ADR 0119](0119-compose-files.md)), the third part of the Docker work builds images from Dockerfiles and starts
containers from images. A Dockerfile was shown by `DockerfileFileViewer` in `file-viewers`, a code viewer whose name
pattern `dockerfile\..+` ranks below extension viewers, so `Dockerfile.md` stays Markdown. A build takes values the
user should not see in a terminal (build args with tokens), and so does `docker run` (container variables). Round 2
passed compose variables in the process environment under their own names; review showed two faults with that: the
login shell's start files (`.zshrc`, …) run after the environment is set and can replace a name such as `JAVA_HOME`
or `HTTP_PROXY`, and a name the terminal or the CLI reads itself (`SHELL`, `HOME`, `DOCKER_HOST`, `DOCKER_CONFIG`)
changes the terminal's shell or, for build args and container variables, which are meant for the image or the
container only, the CLI.

## Decision
- **Dockerfiles open with a view of the `docker` module.** `DockerfileBuildViewer` replaces `DockerfileFileViewer` and
  shows Source | Split | Build (opens in Split); Build is a pane with Build and Run…, tags with a history per file,
  the context folder, the target stage (from `FROM … AS`), a build-args table prefilled from the `ARG` lines,
  `--no-cache`, `--pull`, the platform, a command preview and the Output (`RunPane` with a `CommandRunner`,
  [ADR 0088](0088-shared-command-runs.md)). Explorer Run builds. The options are stored per file in the project data
  (field `dockerBuild`). Logic lives in the FX-free `BuildPaneModel`, `BuildRules`, `BuildCommands` and the pure
  `parseDockerfile` ([ADR 0084](0084-testability-logic-out-of-controls.md)).
- **Weak name patterns** (amends ADR 0029 and 0119). `NamedFileViewer.weakFileNamePatterns` are whole-name,
  case-insensitive patterns that rank like a code viewer's name patterns (PATTERN, below extensions), so a viewer
  outside `CodeFileViewer` can claim `Dockerfile.<anything>` without taking `Dockerfile.md` from Markdown.
  `fileNamePatterns` (ADR 0119) stay the strong kind that beat extensions. The ranking reuses `FileMatch`.
- **Run Container dialog.** Run… on an image in the Docker tab and `Docker.runImage(reference)` (called by the Build
  side after a tagged build that ended with exit code 0) open a dialog with name, ports, environment, volumes, network,
  command, Detached and `--rm`, a preview and Copy. A detached run goes through `DockerCli.run` (which requires `-d`)
  as a background task and then shows the container's logs in the Docker tab; an attached run opens a terminal tab.
  The values are remembered per image repository (the reference without tag or digest) in the project-level data
  (field `dockerRuns`, in the shared file without a project); untagged images are not remembered. The dialog is owned
  by the window of its `Docker` tool (`Docker.window`, set by `ide`).
- **Values outside the command line** (amends ADR 0119). Build-arg values, container variables of an attached run and
  compose variables go into the terminal's environment under private names (`SLUIDE_BUILD_ARG_<i>`,
  `SLUIDE_RUN_ENV_<i>`, `SLUIDE_COMPOSE_ENV_<i>`); the command line names them in a double-quoted word,
  `--build-arg "NAME=$SLUIDE_BUILD_ARG_0"`, `-e "NAME=$SLUIDE_RUN_ENV_0"` and `env "NAME=$SLUIDE_COMPOSE_ENV_0" …` in
  front of compose. The shell expands them after its start files ran, so a start file cannot replace them, the
  terminal's own environment (`SHELL`, `HOME`) stays as it was found, and the values never show in the terminal. For
  build args and attached runs the CLI's environment stays untouched too; compose gets its variables under their own
  names, as it reads them to fill in the file, so a `DOCKER_HOST` row there still points compose at that engine. `NAME` is checked as
  `[A-Za-z_][A-Za-z0-9_]*`, so nothing typed stands unquoted in the word. A detached run, which no terminal shows,
  passes `-e NAME=value` as arguments of the CLI; Thoreau's log masks those values. The Run dialog's preview masks
  values and Copy gives a self-contained command with them.

## Consequences
- Dockerfiles get the same Split as compose files and shell scripts; diffs and Markdown code fences keep their
  highlighting through `Highlighters.dockerfile` and the language names.
- `file-viewers` no longer knows Dockerfiles as a viewer; tests that ranked them moved to the `docker` module and
  `SpringWiringTest`.
- Values with private names work in POSIX shells and fish; csh is not supported, as for the Docker tab's Shell.
  A detached run's values are visible in the process list while the CLI runs, as `docker inspect` shows them anyway.
- No push or registry login, no Dockerfile gutter markers, Podman unverified
  ([../ideas/docker-later.md](../ideas/docker-later.md)).
- Features: [dockerfile.md](../features/dockerfile.md), [docker.md](../features/docker.md) (Run Container),
  [docker-compose.md](../features/docker-compose.md).
