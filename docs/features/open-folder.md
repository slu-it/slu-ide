# Open Folder

**Module:** `ide` — `MainWindow`, `ProjectService`, `ProjectWindows`; decision in
[ADR 0014](../adr/0014-project-windows.md)

- Menu **File → Open Folder…** (Ctrl/Cmd+O) shows a `DirectoryChooser`, starting in the window's project folder.
  On Linux, Ctrl+O does not open the dialog while a terminal has the focus: the terminal sends it to the shell as ^O
  ([terminal.md](terminal.md)); the menu still works.
- One project folder per window:
  - in a window without a folder (the window at startup), the chosen folder opens in that window;
  - otherwise it opens in a new window, placed 30 px below and right of the current one. The current window and its
    tabs stay as they are;
  - if a window already shows the folder, that window comes to the front instead.
- A window's folder is held by its `ProjectService.projectFolder` (observable, read-only, set once via
  `ProjectService.open`, which rejects non-directories and a second, different folder). `MainWindow` passes it to the
  file explorer and the git status ([git-status.md](git-status.md)) and sets the window title to
  `<folder name> – Thoreau`.
- Every window has its own explorer, editor area, terminals, Docker sandboxes and git status (Spring scope `window`).
- Closing a window with unsaved files first asks to save them: Save, Don't Save or Cancel
  ([text-editing.md](text-editing.md)); Cancel keeps the window.
- Closing a window while its tabs still run something (terminal programs, Docker sandboxes and other commands) asks
  once, listing them: "Close this window anyway?". Cancel keeps the window; Close ends them. Without running tabs it
  closes right away. Closing the last window quits Thoreau. Tests replace the dialog via `MainWindow.confirmCloseWindow`.
- Command line: `Thoreau [folder…]` (or `java -jar ide-*.jar [folder…]`) opens the given folders at startup, the
  first in the startup window and every other one in a new window, like Open Folder. Relative paths resolve against
  the working directory. Arguments that are not folders are reported on stderr (`Thoreau: not a folder, ignored: …`)
  and skipped (`startupFolders` in `SluIdeApplication.kt`). Arguments are only folders: none are passed to Spring,
  and arguments of the form `--name=value` (JavaFX "named parameters") are ignored without a warning.
- Nothing is persisted yet: without arguments the IDE starts with one window without a project.
