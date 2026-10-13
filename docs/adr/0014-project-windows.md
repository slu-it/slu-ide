# 0014. One project per window, with window-scoped Spring beans

- Status: accepted
- Date: 2026-10-13

## Context
Opening another folder replaced the project of the only window and closed all its tabs without asking, including
running terminals and Docker sandboxes. Users want to work on several projects at once and not lose running
sessions by accident. All beans were app-wide singletons created at startup, and several of them hold the state of
one project: the main window, the project folder, the explorer and editor area (JavaFX nodes, which can be in one
scene only), the terminals, the sandbox numbering and the git status.

## Decision
- **One project per window.** File → Open Folder opens the folder in the current window only if that window has no
  folder yet (the window at startup); otherwise in a new window. A folder that is already open brings its window to
  the front. `ProjectService.open` refuses a second, different folder.
- **Spring scope `window`** (`WindowScope` in `ide`, registered by `WindowScopeConfiguration`): beans annotated
  `@Scope("window")` exist once per window. `ProjectWindows` creates a window's beans inside `WindowScope.inWindow`
  on the FX thread, shows it, and destroys them when the window is hidden (calling `close()` of `AutoCloseable`
  beans such as `Terminals` and `GitStatusService`). Modules only use the string `"window"`, no new dependency.
  Window-scoped: `MainWindow`, `ProjectService`, `FileExplorerView`, `EditorArea`, `Terminals`, `DockerSandbox`,
  `GitStatusService`. Shared: settings, `FileWatcher`, `FileViewers`, `GitCli`, `ThemedWindows`.
- **Closing a window** collects the `closeWarning`s of all its tabs and, if there are any, asks once ("Close this
  window anyway?"). The last window closing quits the app as before.

Rejected:
- **Prototype beans**: every injection point would get its own instance, so `MainWindow` and `DockerSandbox` would
  not share the window's `Terminals`.
- **A child application context per window**: clean separation, but the window beans would have to be kept out of
  the component scan of the root context and registered by hand.
- **Replacing the project in place after a confirmation**: still ends running sessions to switch projects.

## Consequences
- Several projects can be open at once; each window has its own terminals, sandboxes and git status.
- Window-scoped beans cannot be injected into singletons; doing so fails at startup ("only inside a window").
- The explorer and editor area are now created on the FX thread when a window opens, not at context startup.
- Quitting from the macOS application menu (Cmd+Q) may end the app without close requests, so without the
  confirmation; not verified, since development runs on Linux.
