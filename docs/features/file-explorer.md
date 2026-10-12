# File Explorer

**Module:** `modules/file-explorer` (package `dev.sluide.fileexplorer`)

- `FileExplorerView` is a `TreeView<Path>` (Spring `@Component`). `open(root)` shows the folder with the root
  expanded; `open(null)` clears the view.
- Shows everything: hidden files, dot-folders, nested folders. Nothing is filtered.
- Folders are listed before files; each group is sorted by name, case-insensitively (`listDirectory`).
- Children are loaded lazily when a folder is first expanded (`FileTreeItem`), so large trees and symlink cycles
  are not traversed eagerly. Unreadable folders appear empty.
- Double-click or Enter on a file calls `onOpenFile` (folders keep their expand behavior). `MainWindow` passes the
  file to the editor area, which opens it only if a file viewer accepts it ([editor-tabs.md](editor-tabs.md)).
- Changes on disk show up by themselves. Every loaded folder is watched ([file-watching.md](file-watching.md)), and
  on a change `FileTreeItem.refresh()` re-reads it. Unchanged items are kept, so expanded folders stay expanded
  and the selection stays.
- Manual refresh: "Refresh" in the tree's context menu, or F5, re-reads all loaded folders.
- Not yet implemented: file icons, file operations (new, rename, delete) in the context menu.
