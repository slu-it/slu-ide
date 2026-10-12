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
- Not yet implemented: file icons, refreshing on file-system changes, context menu.
