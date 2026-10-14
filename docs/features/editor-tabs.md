# Editor Tabs

**Module:** `modules/editor` (package `dev.sluide.editor`), depends on `modules/file-viewers` and
`modules/file-watcher`

- `EditorArea` (Spring `@Component`) is the central area right of the file explorer. `open(path)` shows a file in a
  new tab, using the view from `FileViewers`, and selects it. A file that is already open just gets its tab selected.
  If no viewer can open the file, or reading it fails, nothing happens and `open` returns `false`; `canOpen(path)`
  asks the first part beforehand (the file explorer uses it to pick text or binary icons).
- Files are shown read-only, in the editor font from the settings ([settings.md](settings.md)); open views follow
  font changes.
- Tabs do not have to show files: `open(tab: EditorTab)` adds any tab (title, which may change, tooltip and content
  node) and selects it, e.g. a terminal ([terminal.md](terminal.md)). Every way of closing a tab calls its
  `EditorTab.dispose()` once. `openPaths` and `selectedPath` only report file tabs.
- A tab can have a `closeWarning` (e.g. a terminal with a running program). Closing such tabs from the UI (×, middle
  click, any context menu entry) first shows one confirmation dialog listing the warnings; Cancel closes nothing.
  `closeAll()` (used when the window is closed, after the window's own confirmation) does not ask. Tests replace the dialog via
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
  (`EditorArea.projectFolder`, set by the main window), whose files HTML previews may use.
- Open files are watched ([file-watching.md](file-watching.md)). When a file changes on disk, its tab reloads and
  keeps its scroll position and caret (`FileView.reload`); if reading it fails, the tab keeps showing the last content
  and the next change tries again. When the file is deleted, renamed away or can no longer
  be shown (e.g. it became binary), its tab closes.
- Diffs ([git-diff.md](git-diff.md)): `diffOf(path)` returns a `TextDiff` for files to show side by side with their
  original (`FileViewers.openDiff`), `null` for the normal view. It is asked when a file is opened, when it changes on
  disk and for all open files on `refreshDiffs()`; a tab then swaps between diff and normal view in place
  (`OpenFile.view`, inside a stable content node), keeping its tab position and whether it is selected; the scroll
  position resets ([git-diff.md](git-diff.md)).
- Every window has its own editor area ([open-folder.md](open-folder.md)).
- When the tabs do not fit, ‹ and › buttons appear. They scroll the tab row one tab at a time without changing the
  selection. A ▾ dropdown lists every tab that is cut off or out of view, and choosing one selects it and scrolls it
  into view. The selected tab is always scrolled into view when it changes.
- Structure: `EditorTabs` (model: open tabs, `OpenFile` for files, and the selection), `TabOverflow` (which tabs fit, scroll positions,
  where a dragged tab belongs),
  `TabStrip` (header row and layout), styles in `editor.css`.
- Not yet implemented: editing, keyboard shortcuts for tabs, dragging tabs into another window or out of the tab
  row, cancelling a drag with Escape.
