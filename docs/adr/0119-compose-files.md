# 0119. Compose files as runnable views in `docker`

- Status: accepted; amends [0029](0029-file-name-and-shebang-matching.md) and [0079](0079-yaml-preview.md)
- Date: 2026-11-01

## Context
Compose files are the usual way to start a project's services, and Thoreau already shows the engine's containers
([ADR 0117](0117-docker-through-the-cli.md), [ADR 0118](0118-services-menu.md)). Running `docker compose up` from a
terminal works, but the file's view could start, stop and watch the project in place, as shell scripts run from
theirs ([ADR 0089](0089-running-shell-scripts.md)). Compose files are YAML, so the YAML preview
([ADR 0079](0079-yaml-preview.md)) opened them. Their names come in families (`docker-compose.prod.yml`,
`compose.override.yaml`), and a code viewer's name pattern ranks below a viewer for the extension
([ADR 0029](0029-file-name-and-shebang-matching.md)), so a pattern alone would lose to the YAML preview. Compose also
reads variables from the shell, an `.env` beside the file and `--env-file`s, profiles hide services, and an override
file is picked up only while no `-f` is given.

## Decision
- **A view in the `docker` module.** `ComposeFileViewer` (a `SourceFileViewer`, `NamedFileViewer` and
  `RunnableFileViewer`) shows compose files as YAML source with the modes Source | Split | Compose, in Split. Compose
  is a pane with the toolbar (Up with Detached, Down with Down and Remove Volumes…, Build, Pull, Restart), the options
  (profiles, `--build`, `--force-recreate`, `--remove-orphans`, the project name), the services with their containers,
  the Environment section and the Output, an embedded-terminal `RunPane` with a `CommandRunner` as for shell scripts
  ([ADR 0088](0088-shared-command-runs.md)). The source gets a ▶ in its gutter on each service. Explorer Run is Up.
  The logic is in the FX-free `ComposePaneModel` and pure rules ([ADR 0084](0084-testability-logic-out-of-controls.md)).
- **Names and name patterns.** The viewer claims the names compose looks for (`compose.yaml`, `compose.yml`,
  `docker-compose.yaml`, `docker-compose.yml`) and the patterns `(docker-)?compose.<part>.ya?ml`. Amends ADR 0029 with
  `NamedFileViewer.fileNamePatterns`: whole-name, case-insensitive regexes count like exact `fileNames`, so name
  families such as `docker-compose.<env>.yml` beat the extension viewers; unlike a `CodeFileViewer`'s name patterns,
  which rank below extensions, they must be kept narrow. `canOpen` accepts exactly these names.
- **Compose files leave the YAML preview** (amends ADR 0079); other YAML files keep it.
- **Options per file in the project data**, field `compose` (`ComposeStore` over `ProjectDataStore`,
  [ADR 0090](0090-per-project-metadata-store.md)): Detached, profiles, the Up flags, the project name, the env files
  and the variables; only values that differ from the defaults are written. They are stored 300 ms after the last
  change (typing writes once) and when the view closes; an invalid project name is marked and not stored, variables
  with invalid names are left out.
- **Always `-f`.** Every command names the file, which turns compose's own pick-up of the override off, so the sibling
  `compose.override.yaml` (or `docker-compose.override.yml`) of a default name is added back as a second `-f`; a note
  in the pane names it. The commands run in the file's folder, compose's project directory.
- **Profiles.** The listing (`config`) runs with `--profile '*'` to see every service; Down with every profile too, so
  that it also removes the containers of a profile chosen at Up and unchosen since; Up, Build, Pull and Restart with
  the chosen ones; the per-service verbs regardless, as compose enables the profile of a service named on its command
  line.
- **Environment precedence:** the Environment table (put into the process environment) beats the `--env-file`s,
  which beat the `.env`. No env files configured means compose's default, the `.env` beside the file; a configured
  list passes one `--env-file` per entry (adding the first one keeps an existing `.env` in the list), and an explicit
  empty list passes `--env-file /dev/null`, so nothing is read. Variables never go into the command line, so their
  values do not show in the terminal: they go into the terminal's environment under private names
  (`SLUIDE_COMPOSE_ENV_<i>`), and the line starts with `env "NAME=$SLUIDE_COMPOSE_ENV_<i>" …`, which the login shell
  expands after its start files ran (amended in round 3, ADR 0120: under their own names, a start file could set the
  same name over the table's value). Compose's stderr warnings of `config` give the unresolved variables, shown as a
  warning line and asked about before every Up.
- **Configuration loading:** `docker compose config --format json` through `DockerSession.submit` on attach, after
  each save or reload from disk, after changes of the project name or environment (300 ms debounce) and when the
  engine becomes available; a generation counter drops results of older loads. A failure (invalid YAML, a failed
  validation) is shown with compose's message in place of the services and disables the commands, as an engine that
  is not available or not found does.
- **Service states** come from the window's container list (the session the view subscribes to while it is open),
  joined by the compose project and service labels; one-off containers (`compose run`) are left out, containers of
  services no longer in the file show as orphans. Per-service Stop and Restart act on the service's containers through
  the CLI (no Output); Up runs `up -d <service>` in the Output; Logs and Shell go to the window's `Docker` tool, which
  `ide` registers as a window service beside the `DockerSession` ([ADR 0070](0070-window-services.md)).
- **Questions:** before an Up (the button, explorer Run, a row's Up, the gutter's ▶) the unsaved changes (Save and Up,
  Up Saved Version, Cancel) and the unresolved variables; Down and Remove Volumes asks; nothing else does. An Up asked
  for while the engine is being reached or the configuration read (also right after Save and Up or a change of the
  variables or project name, so that the question is about the new configuration) runs once it is, with a note
  meanwhile. Rerun calls the last started command's entry point again, so it asks the same questions and runs only
  while the commands are enabled.
- **No project-trust check**, as for shell scripts: every run is an explicit click, and `config` runs no project code.

## Consequences
- Compose projects start, stop and show their services from their file; the Docker tab shows their containers too.
- A named viewer's pattern now outranks the extension viewers; a too broad pattern there would steal other files, which
  the KDoc and this ADR warn against.
- The options follow the file in the project data; a moved file keeps them under its new path.
- Podman compose (`podman compose`, which delegates to `docker-compose` or `podman-compose`) is unverified; the
  executable of the settings is used as `<executable> compose …`.
- Out of scope, in [../ideas/docker-later.md](../ideas/docker-later.md): Shortcut+Enter, a picker for extra `-f` files,
  "Open Compose File" from the Docker tab.
- Feature page: [../features/docker-compose.md](../features/docker-compose.md). Listed in [../index.md](../index.md).
