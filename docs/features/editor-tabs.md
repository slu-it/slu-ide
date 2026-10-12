# Editor Tabs

**Module:** `modules/editor` (package `dev.sluide.editor`), depends on `modules/file-viewers` and
`modules/file-watcher`

- `EditorArea` (Spring `@Component`) is the central area right of the file explorer. `open(path)` shows a file in a
  new tab, using the view from `FileViewers`, and selects it. A file that is already open just gets its tab selected.
  If no viewer can open the file, nothing happens and `open` returns `false`.
- Files are shown read-only.
- One tab per file: the file name as title, the full path as tooltip. Clicking a tab selects it.
- Tabs close via their × button or a middle click. Closing the selected tab selects its right neighbour, or the left
  one if there is none.
- Right-clicking a tab opens a context menu: Close, Close Others, Close Tabs to the Left, Close Tabs to the Right,
  Close All. Entries that would close nothing are disabled. Close Others selects the clicked tab; closing to the
  left/right keeps the selection unless the selected tab was closed, then the clicked tab is selected.
- Open files are watched ([file-watching.md](file-watching.md)). When a file changes on disk, its tab reloads and
  keeps its scroll position and caret (`FileView.reload`). When the file is deleted, renamed away or can no longer
  be shown (e.g. it became binary), its tab closes.
- Opening a different project folder closes all tabs ([open-folder.md](open-folder.md)).
- When the tabs do not fit, ‹ and › buttons appear. They scroll the tab row one tab at a time without changing the
  selection. A ▾ dropdown lists every tab that is cut off or out of view, and choosing one selects it and scrolls it
  into view. The selected tab is always scrolled into view when it changes.
- Structure: `OpenFiles` (model: open files and the selection), `TabOverflow` (which tabs fit, scroll positions),
  `TabStrip` (header row and layout), styles in `editor.css`.
- Not yet implemented: editing, keyboard shortcuts for tabs, reordering
  tabs by drag and drop.
