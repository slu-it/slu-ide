# Editor Tabs

**Module:** `modules/editor` (package `dev.sluide.editor`), depends on `modules/file-viewers` and
`modules/file-watcher`

- `EditorArea` (Spring `@Component`) is the central area right of the file explorer. `open(path)` shows a file in a
  new tab, using the view from `FileViewers`, and selects it. A file that is already open just gets its tab selected.
  If no viewer can open the file, or reading it fails, nothing happens and `open` returns `false`; `canOpen(path)`
  asks the first part beforehand (the file explorer uses it to pick text or binary icons).
- Files are shown in the editor font from the settings ([settings.md](settings.md)); open views follow font changes.
  Code, plain text and the sources of previews can be edited and saved ([text-editing.md](text-editing.md)).
- A file tab whose editor has unsaved changes (`EditableFileView.dirty`) shows a dot before its name (`● a.txt`),
  also while its diff is shown (`OpenFile.dirty`). `save()` saves the selected file, `saveAll()` every edited one
  (File → Save, Save All); a failure is shown through `showError` (the window's error dialog) and keeps the changes.
- Tabs do not have to show files: `open(tab: EditorTab)` adds any tab (title, which may change, tooltip and content
  node) and selects it, e.g. a terminal ([terminal.md](terminal.md)). Every way of closing a tab calls its
  `EditorTab.dispose()` once. `openPaths` and `selectedPath` only report file tabs.
- Closing file tabs with unsaved changes from the UI (×, middle click, any context menu entry) first asks once, listing
  them: Save, Don't Save or Cancel (`askToSave`, replaced in tests via `EditorArea.confirmSave`). Save saves them and
  closes nothing if one cannot be saved; Cancel closes nothing. The window asks the same for all its files
  (`confirmCloseAll()`) before it closes. `saveBeforeCheckout(folder)` asks about the files at or below a repository
  before a checkout from the git log changes them ([git-log.md](git-log.md)): "These files have unsaved changes. Save
  them before checking out?" with Save, Check Out Anyway (the changes stay; a tab whose file changes on disk keeps them
  and offers Reload or Keep mine, below) and Cancel (`false`); tests replace it via
  `EditorArea.confirmSaveBeforeCheckout`.
- A tab can have a `closeWarning` (e.g. a terminal with a running program). Closing such tabs from the UI (×, middle
  click, any context menu entry) then shows one confirmation dialog listing the warnings; Cancel closes nothing.
  `closeAll()` (used when the window is closed, after the window's own confirmations) does not ask. Tests replace the dialog via
  `EditorArea.confirmClose`. The dialog ("Close this tab anyway?" / "Close these tabs anyway?", Close and Cancel) is
  `askToClose`, which the main window uses for "Close this window anyway?" too.
- One tab per file: the file name as title, the full path as tooltip. Clicking a tab selects it.
- Dragging a tab header (not its ×) with the left mouse button moves the tab once the mouse has moved 5 px: the
  header follows the mouse inside the tab row, and the other tabs make room as soon as its right edge passes the
  centre of the tab to its right, or its left edge the centre of the tab to its left. Releasing the button drops it
  there; it stays selected. If the tabs do not fit, holding the mouse beyond the left or right end of the row scrolls
  it one tab every 300 ms, so tabs can be dragged to positions out of view. The tab list reports a move as a
  permutation (`EditorTabs.move`), so a moved file keeps its watch and font. This is a plain mouse drag within the tab
  row, not JavaFX drag and drop (`Dragboard`), which is meant for transfers between nodes or applications.
- Tabs close via their × button or a middle click. Closing the selected tab selects its right neighbour, or the left
  one if there is none.
- Right-clicking a tab opens a context menu: Close, Close Others, Close Tabs to the Left, Close Tabs to the Right,
  Close All. Entries that would close nothing are disabled. Close Others selects the clicked tab; closing to the
  left/right keeps the selection unless the selected tab was closed, then the clicked tab is selected.
- Every file view gets the editor area as its `FileViewHost`: `openFile` opens a file like the explorer does,
  `openWebPage` calls `EditorArea.openWebPage`, which the main window sets to the system browser
  (`HostServices.showDocument`, through the `SystemBrowser` bean), and `projectFolder` is the window's project folder
  (`EditorArea.projectFolder`, set by the main window), whose files HTML previews may use. `windowService(type)`
  answers from `EditorArea.windowServices`, which the main window sets to its `HttpClientSession`
  ([ADR 0026](../adr/0026-http-client-window-session.md)).
- Open files are watched ([file-watching.md](file-watching.md)). When a file changes on disk, its tab reloads and
  keeps its scroll position and caret (`FileView.reload`); unsaved changes are never thrown away, the view offers to
  reload or keep them instead ([text-editing.md](text-editing.md)). If reading it fails, the tab keeps showing the last
  content and the next change tries again. When the file is deleted, renamed away or can no longer be shown (e.g. it
  became binary), its tab closes, unless it has unsaved changes: then it stays and saving recreates the file.
- Diffs ([git-diff.md](git-diff.md)): `diffOf(path)` returns the `TextDiff` of a file against its original, `null`
  if it has none. It is asked when a file is opened, when it changes on disk and on `refreshDiffs()`, and its changes
  are marked in the editor (`EditableFileView.showChanges`). A file tab keeps the view it was opened with, its editor
  (`OpenFile.editor`), until it closes. `toggleDiff()` (View → Show Changes) shows the diff side by side over it
  (`FileViewers.openDiff`, `OpenFile.diffView`; the editor is hidden meanwhile and keeps text, caret and undo), or the
  editor again; `canToggleDiff` tells whether the selected tab has a diff to show. The diff's Edit button and Escape
  go back (`DiffFileView.onBack`), and so does a diff whose file has none anymore. Clicking a change marker asks the
  area as `FileViewHost.showChanges(file, line)` to show the diff at that change.
- Renames and deletes in the file explorer ([file-explorer.md](file-explorer.md), ADR 0024): `moved(from, to)` lets
  the tabs of a renamed file or of the files in a renamed folder follow it. The tab keeps its place, selection and
  view (text, caret, undo history, unsaved changes; `FileView.moved`), and its title, tooltip (`OpenFile.path` is
  observable), watch and changes follow the new path; a shown diff gives way to the editor. If the view cannot follow
  or another viewer shows the new name (e.g. `.txt` to `.py`), the file opens anew in a new view in the same place
  (`EditorTabs.replace`), unless the tab has unsaved changes: then it keeps its view. `moved` must run right after
  the rename, as the explorer does: a renamed file's watch would report its old path as deleted, and the watches of
  files in a renamed folder follow the folder without reporting anything. A tab already open at the new path (edited,
  its file deleted) reads the file now there at once. `dirtyUnder(path)` lists the files with unsaved changes at or
  below a path for the delete confirmation, and `closeUnder(path, discarded)` closes the tabs there once deleted,
  without asking to save, if they are clean or their changes were among those counted as lost; tabs edited since
  stay with the "deleted" banner. A file renamed outside the IDE looks like a delete to its tab; tabs of files in a
  folder renamed outside the IDE keep their old paths.
- Every window has its own editor area ([open-folder.md](open-folder.md)).
- When the tabs do not fit, ‹ and › buttons appear. They scroll the tab row one tab at a time without changing the
  selection. A ▾ dropdown lists every tab that is cut off or out of view, and choosing one selects it and scrolls it
  into view. The selected tab is always scrolled into view when it changes.
- Structure: `EditorTabs` (model: open tabs, `OpenFile` for files, and the selection), `TabOverflow` (which tabs fit, scroll positions,
  where a dragged tab belongs),
  `TabStrip` (header row and layout), styles in `editor.css`.
- Not yet implemented: keyboard shortcuts for tabs, dragging tabs into another window or out of the tab
  row, cancelling a drag with Escape.
