# Project picker

**Module:** `modules/projects` (package `dev.sluide.projects`) — `RecentProjects` (the remembered folders),
`RecentProject` (folder, last opened, `name`, `displayPath`), `ProjectPicker` (the window); shown by `ProjectWindows`
in `ide`. Decision in [ADR 0034](../adr/0034-project-picker.md)

- Every folder Thoreau opens is remembered: chosen in the picker, with File → Open Folder… or given on the command
  line ([open-folder.md](open-folder.md)). The list is stored in `~/.sluide/projects.properties` (folder path → time
  it was last opened). A file that cannot be read is left alone and the picker lists nothing; errors writing it are
  logged and do not stop the project from opening.
- The picker is a window of its own, titled "Thoreau". It appears
  - at startup when no folder is given on the command line (folders given there open right away, without it);
  - when the last project window is closed, instead of quitting;
  - with **File → Open Project…** in a project window (right after Open Folder…), to open another project. A picker
    already showing comes to the front instead of a second one (`ProjectWindows.pickProject`), also when the last
    window closes while it is open.
- Entries, top to bottom:
  - **Open new project…** shows the folder chooser, starting in the home folder; the chosen folder opens. Cancelling
    the chooser keeps the picker open.
  - The remembered projects, newest first: the folder name in bold, below it the path (`~/…` below the home folder,
    the absolute path elsewhere), and a delete icon on the right ("Remove from list").
- A click on an entry, or Enter on the selected one, opens it: the picker closes and the folder opens in a new window,
  or its window comes to the front if it is already open.
- The delete icon, Delete or Backspace removes the selected project from the list; the selection stays on the same row,
  so the next one can be removed right away. Only the entry is forgotten, the folder itself is never touched.
  "Open new project…" cannot be removed.
- Folders that no longer exist stay in the list, dimmed, with "— folder not found" after the path. They cannot be
  opened, only removed. A folder deleted while the picker is open is marked like that when it is chosen.
- A chosen folder that cannot be opened (e.g. deleted in the meantime) gives an error message naming it, and the
  picker appears again, also while project windows are open.
- Escape or closing the picker cancels it. With no project window open, that quits Thoreau; with windows still open
  it only closes the picker. The picker never appears while Thoreau shuts down.
- Not verified on macOS: quitting with Cmd+Q closes the windows, after which the picker appears and has to be closed
  too ([ADR 0034](../adr/0034-project-picker.md)).
