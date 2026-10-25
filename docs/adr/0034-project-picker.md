# 0034. Remembered projects and the project picker

- Status: accepted
- Date: 2026-10-15
- Storage superseded by [ADR 0090](0090-per-project-metadata-store.md) (2026-10-25)

## Context
Thoreau forgot every folder it had opened. Without command-line arguments it started with an empty window, and
closing the last window quit the app, so getting back to a project always meant File → Open Folder… and the folder
chooser. Users want the folders they work on offered again, at startup and when they are done with a project, without
quitting and restarting.

## Decision
- **Remembered projects** live in `modules/projects` (`RecentProjects`, a singleton bean) and are stored in
  `~/.sluide/projects.properties`: the key is the absolute, normalized folder path, the value the epoch milliseconds
  of the last time it was opened. `MainWindow` records every folder its window opens, so folders chosen in the picker,
  with Open Folder… and on the command line are all remembered. As with the settings ([ADR 0008](0008-settings.md)),
  a file that cannot be read is never replaced, and write errors are only logged: losing the list must not stop a
  project from opening.
- **The project picker** (`ProjectPicker`) is its own `Stage` titled "Thoreau" (since followed by the version, see
  `features/project-picker.md`): "Open new project…" (the folder chooser), then the remembered projects, newest
  first. It is shown at startup without command-line folders and whenever the last project window closes
  (`ProjectWindows.start`, `ProjectWindows.showPicker`). Choosing a project hides the picker and opens it like Open
  Folder…; a folder that is already open brings its window to the front.
- **Quitting**: `SluIdeApplication.start` calls `Platform.setImplicitExit(false)`, so the last window closing no longer
  ends the app. The app ends only when the picker is cancelled (closed or Escape) while no project window is open:
  `ProjectWindows` then calls `Platform.exit()`, and `stop()` closes the Spring context. Closing the context
  destroys the beans of any remaining windows without showing the picker; their stages end with the JavaFX toolkit.
- **No dead ends**: since nothing quits on its own any more, a failure must not leave Thoreau running with neither a
  window nor the picker. `ProjectWindows.open` checks that the folder still exists before it makes a stage; a window
  that fails while it is created or shown, with any exception or error, is hidden and its beans destroyed, also those
  created only in part; a folder that does not open, chosen in the picker or given on the command line, is logged and
  named in an error message, and the other command-line folders still open. Whenever no window is showing afterwards,
  also after a failure while closing a window, the picker is shown again (never a second one over a picker on screen).
- **Command-line folders** open right away, without the picker, and are remembered like any other.
- **Missing folders** stay in the list, dimmed and marked "folder not found"; they cannot be opened but can be
  removed. A folder deleted while the picker is open is marked when it is chosen instead of opened. Removing an entry
  (delete icon, Delete or Backspace) only forgets it; the folder itself is never touched.

Rejected:
- **Keeping implicit exit** and showing the picker from the last window's hidden event: JavaFX decides to exit when
  the last window is hidden, so the picker would race the shutdown it is meant to replace.
- **A modal `Dialog` as the picker**: there is no window for the picker to be modal to at startup or after the last
  window closed, and `showAndWait` would run a nested event loop inside `start()` and inside the handler of the
  closing window for as long as the picker is open. The short error alerts about folders that do not open do use
  `showAndWait`, so the picker returns only once the message is read.
- **Storing the list in `settings.properties`**: the settings are applied only once stored and edited in the settings
  dialog; a list rewritten on every opened folder would mix usage history into the user's configuration.

## Consequences
- Thoreau keeps running without a project window as long as the picker is open; closing the picker is the way to quit.
- Every folder ever opened stays in the list until the user removes it; there is no limit or expiry yet.
- Not verified on macOS, since development runs on Linux: Cmd+Q asks every window to close, which should leave the
  picker on screen, so quitting from the application menu would also need the picker to be closed.
- Partly supersedes [ADR 0014](0014-project-windows.md) (the last window closing quit the app).
