# 0118. Services menu: window tools for external services, the Docker tab first

- Status: accepted; amends [0110](0110-tools-menu.md) (tools may have accelerators; `ToolsMenu` is reused per menu)
- Date: 2026-11-01

## Context
Thoreau gets a Docker tab that lists and manages the containers, images, volumes and networks of the engine
([ADR 0117](0117-docker-through-the-cli.md)). Like the tools of the Tools menu it belongs to no file, keeps state the
user comes back to and is shown once per window. Unlike them it manages something outside the IDE that runs on its
own, and it is opened often, so it wants a key. IntelliJ groups such tool windows (Docker, databases, run
configurations) under Services, on Alt+8.

## Decision
- A **Services** menu after Tools, for window tools that manage external services. Its first and only item is
  **Docker**, on **Alt+8** (Cmd+8 on macOS, as Alt+1, Alt+6 and Alt+9 are).
- The Services menu is a second instance of `ToolsMenu` (it takes the menu title, default "Tools"); `WindowTool`
  gets an optional `accelerator`. A tool behaves as in ADR 0110 whatever menu it is in: one tab per window, a second
  choice selects it, Shortcut+W closes it, it is not restored after a restart.
- The `ide` part stays inside `WindowTools` (`servicesMenu()`, `dockerTool`), so nothing new is named like the
  `WindowServices` registry ([ADR 0070](0070-window-services.md)).
- The `docker` module offers a window bean **`Docker`** as its API: `createTab()`, `showTab` and `openTab` (set by
  `ide`), `openUrl` (the system browser), `projectFolder` (bound to the window's), `showContainer(id, logs)` for other
  views to show a container (optionally its logs) and `openShell(containerId, name)` for a shell in a container as a
  terminal tab. `MainWindow` registers the window's `DockerSession` as a window service for the Compose and Dockerfile
  views to come.
- The tab's logic lives outside its controls ([ADR 0084](0084-testability-logic-out-of-controls.md)): the tree is
  built by a pure function and kept in step by `TreeSync`, which keeps the items of resources that are still there,
  so expansion and selection survive the frequent reloads; `DockerTabModel` holds selection, details loading and the
  error bar; enablement, overviews, port links and the shell command are pure functions; the questions go through an
  FX-free `DockerDialogs`.
- Long operations (pull, prune, removing an image, volume or network, creating one) are rows of the window's background
  tasks ([ADR 0061](0061-background-tasks.md)); a pull is cancellable there. Short container actions are not.

## Consequences
- Further service tools (databases, Kubernetes) get a `WindowTool` in the Services menu and their tab in their module.
- Alt+8 is taken in the whole window; in a focused terminal on Linux and Windows it goes to the shell, like Alt+1.
- `docker` now depends on `terminal` (`api`, for `Terminals` in `Docker`'s constructor), `editor-area` and
  `background-tasks` (`api`) and `file-viewers` (the Inspect JSON); see
  [../architecture.md](../architecture.md).
- Feature page: [../features/docker.md](../features/docker.md); listed in [../index.md](../index.md).
