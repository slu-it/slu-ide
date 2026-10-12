# Open Folder

**Module:** `ide` — `MainWindow`, `ProjectService`

- Menu **File → Open Folder…** (Ctrl/Cmd+O) shows a `DirectoryChooser`, starting in the current project folder.
- The chosen folder becomes the *current project folder*, held by `ProjectService.projectFolder` (observable,
  read-only; changed only via `ProjectService.open`, which rejects non-directories).
- On change, `MainWindow` passes the folder to the file explorer and sets the window title to `<folder name> – SluIde`.
- Nothing is persisted yet: the IDE starts without a project.
