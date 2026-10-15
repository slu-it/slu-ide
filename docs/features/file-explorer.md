# File Explorer

**Module:** `modules/file-explorer` (package `dev.sluide.fileexplorer`)

- `FileExplorerView` is a `TreeView<Path>` (Spring `@Component`). `open(root)` shows the folder with the root
  expanded; `open(null)` clears the view.
- Uses the explorer font from the settings and follows its changes ([settings.md](settings.md)).
- Shows everything: hidden files, dot-folders, nested folders. Only `.git` (folder, or file in worktrees and
  submodules) is left out.
- Folders are listed before files; each group is sorted by name, case-insensitively (`listDirectory`).
- Children are loaded lazily when a folder is first expanded (`FileTreeItem`), so large trees and symlink cycles
  are not traversed eagerly. Unreadable folders appear empty.
- Double-click or Enter on a file calls `onOpenFile` (folders keep their expand behavior). `MainWindow` passes the
  file to the editor area, which opens it only if a file viewer accepts it ([editor-tabs.md](editor-tabs.md)).
- Changes on disk show up by themselves. Every loaded folder is watched ([file-watching.md](file-watching.md)), and
  on a change `FileTreeItem.refresh()` re-reads it. Unchanged items are kept, so expanded folders stay expanded
  and the selection stays. A loaded subfolder that is deleted and recreated (e.g. `build/`) is watched again.
- Limitation: if the opened folder itself is deleted, its watch ends; recreated later, it is not watched again (it has
  no parent in the tree that would notice) until "Refresh"/F5 or reopening it.
- Manual refresh: "Refresh" in the tree's context menu, or F5, re-reads all loaded folders, checks again which of
  their files can be opened (icons) and renews their watches.
- Icons ([ADR 0012](../adr/0012-file-icons.md), `FileIcon`/`fileIconFor`): open or closed folder; for files, every
  file type icon of Material Design Icons: languages (the code viewer types plus C/C++/C#, Go, Rust, Ruby, PHP, Swift,
  CSS/Sass, shell, SQL, …, and a generic code icon for other languages), tools (Docker, Gradle, Node.js, Git,
  Terraform, Make, …), documents (PDF, Word, Excel, PowerPoint, CSV), media (images, video, audio, fonts, 3D/CAD),
  archives, packages, installers (Android, Apple, Windows) and settings/keys/certificates/lock files. Each `FileIcon`
  lists its file names (e.g. `Dockerfile`, `package.json`, `LICENSE`), which win, and its extensions, all
  case-insensitive. Any other file gets a document icon if a viewer can open it (`canOpenFile`, set by `MainWindow` to
  `EditorArea.canOpen`) and a "?" file icon if not (binary); the answer is cached and asked again when the file
  changes, or for every file of a folder that lost events or on a manual refresh. Colours per type or family in
  `file-explorer.css`, light and dark; the icon size follows the explorer font.
- Markers (`FileMarkers` of `FileMarker`s): a colour for the name and coloured bubbles after it, asked from the
  `markers` callback for each shown entry and again after `refreshMarkers()`. `MainWindow` uses them for the git
  status ([git-status.md](git-status.md)); `onFilesChanged` reports changes on disk and manual refreshes so the status
  follows. On the selected row the name has the selection's text colour, so it stays readable, and bubbles get a ring
  in that colour. Colours light and dark in `file-explorer.css`.
- File operations ([ADR 0024](../adr/0024-file-operations-in-the-explorer.md), `FileOperations`), from the context
  menu of an entry (right-click, or the context-menu key for the selected one) or of the empty area below the entries,
  which stands for the opened folder:
  - New File… / New Folder… create an empty entry in the clicked folder, or beside the clicked file. A new file is
    opened. Single names only (no `a/b`).
  - Rename… (F2 on the selected entry) renames within the same folder; the dialog starts with the name, its base name
    (before the last dot, not a leading one) selected. A folder keeps its content and stays expanded. Changing only
    the case of a name works on case-insensitive file systems.
  - Delete… (Delete, or Cmd+Backspace on macOS / Ctrl+Backspace elsewhere, on the selected entry) deletes permanently,
    a folder with everything in it and a link without its target, after a confirmation that names the entry, says it
    is not moved to the trash and counts the files with unsaved changes that will be lost. Cancel is the default
    button. If the entry was selected, the next one (or the one before, or its folder) is selected at once. Deleting
    runs in the background, so a large folder does not block the IDE; meanwhile the entry cannot be renamed or deleted
    again. If the entry became something else while asking (e.g. a file a folder), it is not deleted. A failed delete stops at the first entry it cannot delete and can leave a
    folder partly deleted; the error is shown and `onDeleted` is not called.
  - The name dialog checks the name while typing (not empty, no `/`, `\` or NUL, not `.`/`..`, no leading or
    trailing whitespace, no characters the file system cannot store, not taken, on case-insensitive file systems also
    not in another case) and keeps OK disabled while it shows a problem. Errors on disk (e.g. permissions) are shown in an error dialog.
  - The result shows at once, without waiting for the folder's watch, and is selected. Rename and Delete are disabled
    for the opened folder itself.
  - For open tabs, `onMoved(from, to)` reports renames, `onDeleted(path, unsaved)` deletions with the files the
    confirmation counted as lost, and `unsavedUnder(path)` is asked for the files with unsaved changes. `MainWindow`
    connects them to the editor area ([editor-tabs.md](editor-tabs.md)): tabs of a renamed file, or of the files in a
    renamed folder, stay open at the new path with their unsaved changes; the confirmation of a delete counts the
    unsaved files at or below the entry, and once it is deleted their tabs and those without unsaved changes close
    without asking again. Files edited after the confirmation keep their tabs, showing the file deleted.
