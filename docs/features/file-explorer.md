# File Explorer

**Module:** `modules/file-explorer` (package `dev.sluide.fileexplorer`)

- `FileExplorerView` is a `TreeView<Path>` (Spring `@Component`). `open(root)` shows the folder with the root
  expanded; `open(null)` clears the view.
- Uses the explorer font from the settings and follows its changes ([settings.md](settings.md)).
- Shows everything: hidden files, dot-folders, nested folders. Nothing is filtered.
- Folders are listed before files; each group is sorted by name, case-insensitively (`listDirectory`).
- Children are loaded lazily when a folder is first expanded (`FileTreeItem`), so large trees and symlink cycles
  are not traversed eagerly. Unreadable folders appear empty.
- Double-click or Enter on a file calls `onOpenFile` (folders keep their expand behavior). `MainWindow` passes the
  file to the editor area, which opens it only if a file viewer accepts it ([editor-tabs.md](editor-tabs.md)).
- Changes on disk show up by themselves. Every loaded folder is watched ([file-watching.md](file-watching.md)), and
  on a change `FileTreeItem.refresh()` re-reads it. Unchanged items are kept, so expanded folders stay expanded
  and the selection stays.
- Manual refresh: "Refresh" in the tree's context menu, or F5, re-reads all loaded folders and renews their watches.
- Icons ([ADR 0012](../adr/0012-file-icons.md), `FileIcon`/`fileIconFor`): open or closed folder; for files, every
  file type icon of Material Design Icons: languages (the code viewer types plus C/C++/C#, Go, Rust, Ruby, PHP, Swift,
  CSS/Sass, shell, SQL, …, and a generic code icon for other languages), tools (Docker, Gradle, Node.js, Git,
  Terraform, Make, …), documents (PDF, Word, Excel, PowerPoint, CSV), media (images, video, audio, fonts, 3D/CAD),
  archives, packages, installers (Android, Apple, Windows) and settings/keys/certificates/lock files. Each `FileIcon`
  lists its file names (e.g. `Dockerfile`, `package.json`, `LICENSE`), which win, and its extensions, all
  case-insensitive. Any other file gets a document icon if a viewer can open it (`canOpenFile`, set by `MainWindow` to
  `EditorArea.canOpen`) and a "?" file icon if not (binary). Colours per type or family in `file-explorer.css`, light
  and dark; the icon size follows the explorer font.
- Markers (`FileMarker`): coloured bubbles after the name, asked from the `markers` callback for each shown entry
  and again after `refreshMarkers()`. `MainWindow` uses them for the git status ([git-status.md](git-status.md));
  `onFilesChanged` reports changes on disk and manual refreshes so the status follows. On the selected row they get a
  ring in the selection's text colour. Colours light and dark in `file-explorer.css`.
- Not yet implemented: file operations (new, rename, delete) in the context menu.
