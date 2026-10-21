# File Explorer

**Module:** `modules/file-explorer` (package `dev.sluide.fileexplorer` and its sub-packages, [ADR 0055](../adr/0055-sub-packages-within-modules.md))

- `FileExplorerView` is a `TreeView<Path>` (Spring `@Component`). `open(root, extraRoots)` shows the project folder
  `root` expanded and after it each `ExplorerRoot(folder, label, folderIcons)` of `extraRoots` collapsed, as top-level nodes
  below a hidden tree root; `open(null)` clears the view. The window passes the scratches folder as "Scratches"
  ([scratch-files.md](scratch-files.md)); the explorer knows nothing about scratches.
  `ExplorerRoot.folderIcons` (`FolderIcons(top, closed, open)`, default `null` = the normal folder icons) replaces the
  icons of the root (`top`, expanded or not) and of every folder below it (`closed`/`open`); files keep their type
  icons, and the project tree is unaffected. The window uses it to colour the scratches (teal, `folder-edit` root).
  An extra root's folder must exist when `open` runs. Extra roots show their label instead of the folder name, with the full path as tooltip, and are
  loaded and watched once expanded.
- `reveal(path, focus = false)` expands the top-level node holding `path` (the innermost one if they nest) and the
  folders down to it, loading them, then selects and scrolls to it; it takes the focus only if `focus`. It returns
  `false`, changing nothing, if no top-level node holds the path or it does not exist.
- Uses the explorer font from the settings and follows its changes ([settings.md](settings.md)).
- Width: the window starts the explorer as wide as the names (labels for extra roots) of the shown entries need, with
  their icons and indentation (`startWidth()`), but at least 12 and at most 24 times the explorer font size; without a folder it is
  12 times, and in a narrow window at most half of it. It gets this width when the window opens and when a folder
  opens in it. Resizing the window resizes the editor area; the explorer keeps its width until the divider is dragged,
  unless the window gets so narrow that the editor area cannot shrink any further. Later changes (expanding folders,
  another font) do not change it.
- Shows everything: hidden files, dot-folders, nested folders. Only `.git` (folder, or file in worktrees and
  submodules) is left out.
- Folders are listed before files; each group is sorted by name, case-insensitively (`listDirectory`).
- Children are loaded lazily when a folder is first expanded (`FileTreeItem`), so large trees and symlink cycles
  are not traversed eagerly. Unreadable folders appear empty.
- The selection is multiple (Shift+click or Shift+Up/Down for a range, Shortcut+click to add or remove, Shortcut+A for
  all).
- Double-click on a file calls `onOpenFile` for that file; Enter with files selected (and the Open menu item) calls it
  for every selected regular file in row order (folders keep their expand behavior). `MainWindow` passes the
  file to the editor area, which opens it only if a file viewer accepts it ([editor-tabs.md](editor-tabs.md)) and moves
  the focus into it ([ADR 0050](../adr/0050-focus-when-a-file-tab-is-shown.md)).
- Changes on disk show up by themselves. Every loaded folder is watched ([file-watching.md](file-watching.md)), and
  on a change `FileTreeItem.refresh()` re-reads it. Unchanged items are kept, so expanded folders stay expanded
  and the selection stays. A loaded subfolder that is deleted and recreated (e.g. `build/`) is watched again.
- Limitation: if a top-level folder itself (the project or an extra root) is deleted, its watch ends; recreated later,
  it is not watched again (it has no parent in the tree that would notice) until "Refresh"/F5 or reopening it. Watches
  belong to tree items, so a folder shown twice (an extra root inside the project) is watched by each.
- Manual refresh: "Refresh" in the tree's context menu, or F5, re-reads all loaded folders, checks again which of
  their files can be opened (icons) and renews their watches.
- Show History in the context menu, while the window offers it (`showHistory`; enabled by `canShowHistory`, never for
  folders or the empty area), opens the file's history in the git log area ([file-history.md](file-history.md)).
- Icons ([ADR 0012](../adr/0012-file-icons.md), `FileIcon`/`fileIconFor`, now in the `file-icons` module,
  [ADR 0047](../adr/0047-module-dependencies-and-shared-modules.md)): open or closed folder; for files, every
  file type icon of Material Design Icons: languages (the code viewer types plus C/C++/C#, Go, Rust, Ruby, PHP, Swift,
  CSS/Sass, shell, SQL and KQL (`.kql`, `.csl`) with the database icon, …, and a generic code icon for other
  languages), tools (Docker, Gradle, Node.js, Git, Terraform, Azure for Bicep (`.bicep`, `.bicepparam`), Make, …),
  documents (PDF, Word, Excel, PowerPoint, CSV), media (images, video, audio, fonts, 3D/CAD),
  archives, packages, installers (Android, Apple, Windows), settings/keys/certificates/lock files, and an API icon
  for HTTP client requests (`.http`, `.rest`; [http-client.md](http-client.md)). Each `FileIcon`
  lists its file names (e.g. `Dockerfile`, `package.json`, `LICENSE`), which win, and its extensions, all
  case-insensitive. Any other file gets a document icon if a viewer can open it (`canOpenFile`, set by `MainWindow` to
  `EditorArea.canOpen`) and a "?" file icon if not (binary); the answer is cached and asked again when the file
  changes, or for every file of a folder that lost events or on a manual refresh. Colours per type or family in
  `file-icons.css` (`FILE_ICONS_CSS`), light and dark; the git log's changed files use the same icons
  ([git-log.md](git-log.md)); the icon size follows the explorer font.
- Markers (`FileMarkers` of `FileMarker`s): a colour for the name and coloured bubbles after it, asked from the
  `markers` callback for each shown entry and again after `refreshMarkers()`. `MainWindow` uses them for the git
  status ([git-status.md](git-status.md)); `onFilesChanged` reports changes on disk and manual refreshes so the status
  follows. On the selected row the name has the selection's text colour, so it stays readable, and bubbles get a ring
  in that colour. Colours light and dark in `file-explorer.css`.
- File operations ([ADR 0024](../adr/0024-file-operations-in-the-explorer.md), `FileOperations`), from the context
  menu (`actions/ExplorerMenu.kt`) of the selected entries (right-click, or the context-menu key) or of the empty area
  below the entries, which stands for the project folder. A right-click on a selected row keeps the selection and the
  menu applies to all of it; on another row it is for that row alone. `menuState` decides which items are enabled:
  - Open (first item) opens every regular file among the targets, in row order, skipping folders; enabled when there
    is at least one.
  - New File… / New Folder… create an empty entry in the clicked folder or, on the empty area, in the project folder.
    They are enabled for exactly one folder (also a top-level one) or the empty area while a folder is open; disabled
    on a file and for several entries. A new file is opened. Single names only (no `a/b`).
  - Folder actions (`FolderAction`, set by the window as `folderActions`) follow New Folder…, e.g. "New HTTP Client
    Setup" ([http-client.md](http-client.md)). They run on the same folder and are enabled like New File…; the file an
    action returns is selected (if it is in that folder) and opened, the folder is expanded and read again, a note it
    returns is shown, and an IOException is shown as an error.
  - Rename… (F2) works on exactly one entry that can be changed and renames within the same folder; the dialog starts
    with the name, its base name (before the last dot, not a leading one) selected. A folder keeps its content and
    stays expanded. Changing only the case of a name works on case-insensitive file systems.
  - Delete… (Delete, or Cmd+Backspace on macOS / Ctrl+Backspace elsewhere) works on all selected entries when all can
    be changed. Entries at or below another selected one are left out. One confirmation: for one entry it names it, for
    several it says "Delete N entries?" and lists the first ten names (folders "and everything in it", then "and N
    more"); it counts the files with unsaved changes over all entries and says that nothing is moved to the trash.
    Cancel is the default button. If the selection lay in the deleted entries, the first remaining row after them (or
    the nearest before, or the folder) is selected at once. Deleting runs in the background, in row order, so a large
    folder does not block the IDE; meanwhile the entries cannot be renamed or deleted again. Entries that became
    something else while asking (e.g. a file a folder) are not deleted and reported in one error; the others are. A
    failed delete stops at the first entry it cannot delete (later ones are not tried) and can leave a folder partly
    deleted; the error is shown, `onDeleted` is called for every entry deleted completely, not for the failed one.
  - Show History is enabled for exactly one file (`canShowHistory`), never for folders, several entries or the empty
    area. Refresh is always enabled.
  - The name dialog checks the name while typing (not empty, no `/`, `\` or NUL, not `.`/`..`, no leading or
    trailing whitespace, no characters the file system cannot store, not taken, on case-insensitive file systems also
    not in another case) and keeps OK disabled while it shows a problem. Errors on disk (e.g. permissions) are shown in an error dialog.
  - The result shows at once, without waiting for the folder's watch, and is selected. Rename and Delete are disabled
    on every top-level node (the project folder and the extra roots).
  - For open tabs, `onMoved(from, to)` reports renames, `onDeleted(path, unsaved)` deletions with the files the
    confirmation counted as lost, and `unsavedUnder(path)` is asked for the files with unsaved changes. `MainWindow`
    connects them to the editor area ([editor-tabs.md](editor-tabs.md)): tabs of a renamed file, or of the files in a
    renamed folder, stay open at the new path with their unsaved changes; the confirmation of a delete counts the
    unsaved files at or below the entry, and once it is deleted their tabs and those without unsaved changes close
    without asking again. Files edited after the confirmation keep their tabs, showing the file deleted.
