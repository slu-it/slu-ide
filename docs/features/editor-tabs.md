# Editor Tabs

**Module:** `modules/editor-area` (package `dev.sluide.editorarea`), depends on `modules/file-viewers` and
`modules/file-watcher`

- `EditorArea` (Spring `@Component`) is the central area right of the file explorer. `open(path)` shows a file in a
  new tab, using the view from `FileViewers`, and selects it. A file that is already open just gets its tab selected.
  If no viewer can open the file, or reading it fails, nothing happens and `open` returns `false`; `canOpen(path)`
  asks the first part beforehand (the file explorer uses it to pick text or binary icons).
- Focus ([ADR 0050](../adr/0050-focus-when-a-file-tab-is-shown.md)): opening a file (also one already open) and
  choosing a tab (click on its header, overflow menu) call its view's `FileView.focus()`: the text of code views, the
  source of previews in Source or Split, the web view of images, of previews shown alone and of zooming previews in
  Split (Mermaid, Bicep); the diff instead while one is shown. A file tab selected in place of another (the selected
  one closed or replaced) is focused only if the area had the focus, so deleting the selected file in the tree leaves
  the focus there. A new tab starts at the top, one shown again keeps caret and scroll. Other tabs (terminals, commit
  diffs) are not focused by the area. `focusSelected()` gives the focus back to the selected tab (its content for
  other tabs, the area itself without tabs), e.g. when the git log is collapsed.
- Files are shown in the editor font from the settings ([settings.md](settings.md)); open views follow font changes.
  Code, plain text and the sources of previews can be edited and saved ([text-editing.md](text-editing.md)).
- A file tab whose editor has unsaved changes (`EditableFileView.dirty`) shows a dot before its name (`● a.txt`),
  also while its diff is shown (`OpenFile.dirty`). `save()` saves the selected file, `saveAll()` every edited one
  (File → Save, Save All); a failure is shown through `showError` (the window's error dialog) and keeps the changes.
  `onFileSaved(path)`, set by the window, is called for every file saved from a tab (also before closing or a git
  change), once it is written; the window passes it on to the Gradle session ([gradle.md](gradle.md)).
- Tabs do not have to show files: `open(tab: EditorTab)` adds any tab (title, which may change, tooltip and content
  node) and selects it, e.g. a terminal ([terminal.md](terminal.md)). Every way of closing a tab calls its
  `EditorTab.dispose()` once. `openPaths` and `selectedPath` only report file tabs.
- `onFileClosed(path)`, set by the window, is called for each file whose last tab in the area closed, in whatever way
  (×, the context menu, `closeAll()` when the window closes, after a delete, or a move its tab cannot follow), once
  the change of the tab list is processed; not for other tabs, not when a tab is replaced by a new view of the same
  file, and not for a move its tab follows in place. The window deletes empty scratch files with it
  ([scratch-files.md](scratch-files.md)).
- Closing file tabs with unsaved changes from the UI (×, middle click, any context menu entry) first asks once, listing
  them: Save, Don't Save or Cancel (`askToSave`, replaced in tests via `EditorArea.confirmSave`). Save saves them and
  closes nothing if one cannot be saved; Cancel closes nothing. The window asks the same for all its files
  (`confirmCloseAll()`) before it closes. `saveBeforeGitChange(folder, action)` asks about the files at or below a
  repository before an operation of the git log changes them ([git-log.md](git-log.md)), named by its action ("Check
  Out", "Cherry-Pick", "Reset"…): "These files have unsaved changes. Save them before you check out?" with Save,
  "<action> Anyway" (the changes stay; a tab whose file changes on disk keeps them and offers Reload or Keep mine,
  below) and Cancel (`false`); tests replace it via `EditorArea.confirmSaveBeforeGitChange`.
- A tab can have a `closeWarning` (e.g. a terminal with a running program); a file tab has its view's
  (`FileView.closeWarning`, e.g. a view running a build). Closing such tabs from the UI (×, middle click, Shortcut+W,
  any context menu entry) then shows one confirmation dialog listing the warnings; Cancel closes nothing. The window
  lists the warnings of all its tabs, file views' included, before it closes.
  `closeAll()` (used when the window is closed, after the window's own confirmations) does not ask. Tests replace the dialog via
  `EditorArea.confirmClose`. The dialog ("Close this tab anyway?" / "Close these tabs anyway?", Close and Cancel) is
  `askToClose`, which the main window uses for "Close this window anyway?" too.
- One tab per file: the file name as title, the full path as tooltip. Clicking a tab selects it.
- A file tab shows what its view is doing (`FileView.activity`) as a marker before its title: a small spinner while it
  runs something (`ViewActivity.RUNNING`, style class `tab-activity-running`) and a red dot after it failed
  (`FAILED`, `tab-activity-failed`, lighter in the dark theme); nothing for `NONE`. The view decides how long a
  failure shows (e.g. until its tab is shown again); the tab only displays it.
- Dragging a tab header (not its ×) with the left mouse button moves the tab once the mouse has moved 5 px: the
  header follows the mouse inside the tab row, and the other tabs make room as soon as its right edge passes the
  centre of the tab to its right, or its left edge the centre of the tab to its left. Releasing the button drops it
  there; it stays selected. If the tabs do not fit, holding the mouse beyond the left or right end of the row scrolls
  it one tab every 300 ms, so tabs can be dragged to positions out of view. The tab list reports a move as a
  permutation (`EditorTabs.move`), so a moved file keeps its watch and font. This is a plain mouse drag within the tab
  row, not JavaFX drag and drop (`Dragboard`), which is meant for transfers between nodes or applications.
- Tabs close via their × button or a middle click. Shortcut+W (File → Close Tab, in the whole window, like Save)
  closes the selected tab the same way and asks the same questions, but only for file and diff tabs
  (`EditorTab.closableByShortcut`, `EditorArea.closeSelected`); it is disabled while a terminal or Docker sandbox tab
  is selected, as Ctrl+W belongs to the shell there. Closing the selected tab selects its right neighbour, or the left
  one if there is none.
- Right-clicking a tab opens a context menu: Close, Close Others, Close Tabs to the Left, Close Tabs to the Right,
  Close All. Entries that would close nothing are disabled. Close Others selects the clicked tab; closing to the
  left/right keeps the selection unless the selected tab was closed, then the clicked tab is selected. Tabs that show
  a file on disk have Show History at the end while the window offers it (`EditorArea.showHistory`, enabled by
  `canShowHistory`), which opens the file's history in the git log area ([file-history.md](file-history.md)).
- Every file view gets the editor area as its `FileViewHost` (`ViewHost`): `openFile` opens a file like the explorer
  does, `openFile(file, line)` then lets its view go to the line (`FileView.showLine`, as Go to Line moves the caret),
  `openWebPage` calls `EditorArea.openWebPage`, which the main window sets to the system browser
  (`HostServices.showDocument`, through the `SystemBrowser` bean), and `projectFolder` is the window's project folder
  (`EditorArea.projectFolder`, set by the main window), whose files HTML previews may use. `windowService(type)`
  answers from `EditorArea.windowServices`, an exact-type registry (`WindowServices`) in which the main window
  registers its `HttpClientSession` and `BackgroundTasks`
  ([ADR 0026](../adr/0026-http-client-window-session.md), [ADR 0070](../adr/0070-window-services.md)).
  `currentText(file)` gives the text of a file's tab with
  unsaved changes (`null` if it is not open in a `TextEditableView` or that view is read-only); `editText(file, edit)`
  changes it there as one undo step, unsaved, opening the file in a tab that is not selected (nor focused) if it is
  not open, for views that edit another file ([ADR 0057](../adr/0057-visual-environment-editor.md)); `false` if the
  file cannot be shown in a `TextEditableView` or is read-only, then no tab opens.
- Open files are watched ([file-watching.md](file-watching.md)). When a file changes on disk, its tab reloads and
  keeps its scroll position and caret (`FileView.reload`); unsaved changes are never thrown away, the view offers to
  reload or keep them instead ([text-editing.md](text-editing.md)). If reading it fails, the tab keeps showing the last
  content and the next change tries again. When the file is deleted, renamed away or can no longer be shown (e.g. it
  became binary), its tab closes, unless it has unsaved changes: then it stays and saving recreates the file.
- Diffs ([git-diff.md](git-diff.md)): `diffOf(path, done)` gives `done` the `TextDiff` of a file against its original,
  `null` if it has none, on the JavaFX thread and maybe later; only the answer to a tab's newest request counts, and
  the old markers stay until it arrives ([ADR 0062](../adr/0062-git-concurrency.md)). It is asked when a file is
  opened, when it changes on disk and on `refreshDiffs()`, and its changes are marked in the editor
  (`EditableFileView.showChanges`). A file tab keeps the view it was opened with, its editor (`OpenFile.editor`), until
  it closes. `toggleDiff()` (View → Show Changes) shows the diff side by side over it
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
- Not yet implemented: other keyboard shortcuts for tabs (switching), dragging tabs into another window or out of the tab
  row, cancelling a drag with Escape.
