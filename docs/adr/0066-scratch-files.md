# 0066. Scratch files

- Status: accepted
- Date: 2026-10-21

## Context
Notes, a JSON snippet to look at, a request to try or a few lines of Kotlin often belong to no project. IntelliJ has
scratch files for this: created from a type, named automatically, kept outside the project and offered in every
project. Thoreau had no place for such files; saving them into the project dirties its git status, and saving them
elsewhere needs a folder chooser, which a window cannot show in its explorer.

## Decision
- **One folder shared by all windows: `~/.sluide/scratches`** (`ScratchFiles.folder`, a singleton bean of the
  `scratches` module), next to Thoreau's other files in `~/.sluide`. A scratch is meant to be found again from any
  project, as in IntelliJ. Rejected: scratches per project (lost when the project is not open, and they would have
  to live in the project or in a mapping of projects to folders); IntelliJ's location in the IDE's config directory
  (platform-specific paths for no gain; `~/.sluide` is where Thoreau keeps its state); a symbolic link into the
  project (git would see it, and deleting the project's files would reach the scratches); a virtual explorer node
  without a real folder (the explorer, the file watcher, the editor and every file view work on real paths).
- **Shown as a second top-level node "Scratches"** below the project root, collapsed until opened. The explorer knows
  no scratches: `FileExplorerView.open(root, extraRoots)` takes generic `ExplorerRoot(folder, label)`s, which `ide`
  (`WindowScratches`) passes ([ADR 0047](0047-module-dependencies-and-shared-modules.md): feature modules do not
  depend on each other). The tree's root is hidden, its children are the top-level folders. Watches belong to tree
  items, not paths, so a folder shown twice is watched twice. The folder is created when a window opens a project;
  if that fails, a warning is logged and the window shows only the project.
- **Names without a dialog:** `scratch.<ext>`, else `scratch_<n>.<ext>` with the lowest free n >= 1, created with
  `CREATE_NEW`; a name taken meanwhile (by another window, or a case-insensitive twin) tries the next number.
- **A look of its own:** the Scratches node shows the Material Design icon `folder-edit` (a folder with a pencil),
  the same expanded or collapsed; the folders in it keep the normal closed/open folder glyphs, so the open/closed cue
  stays. All are teal (`FileIcon.SCRATCHES`, `SCRATCH_FOLDER`, `SCRATCH_FOLDER_OPEN`) instead of the folders' amber;
  files keep their type icons. `ide` passes them in as `ExplorerRoot.folderIcons` (`FolderIcons`), so the explorer
  stays generic. The pack's marked folders (`folder-star`, `folder-lock`, ...) are not used for subfolders: they have
  no open variant, and a deep tree of them would get busy.
- **A type picker** (`ScratchTypePicker`, a popup near the top of the window) with a curated list of types (name,
  extension, file-type icon): filter by name or extension, Up/Down, Enter or click. The type chosen last is
  preselected next time; it is kept in memory only, since a setting for it is not worth the storage.
- **File → New Scratch File… on Shortcut+Shift+N** (Cmd+Shift+N on macOS, IntelliJ's key there). It was free, and
  Ctrl+Shift+letter is not sent to the shell, so it works from a terminal tab too
  ([terminal.md](../features/terminal.md)).
  The new file is revealed in the explorer (Scratches expanded, the file selected) and opened in the editor with the
  focus ([ADR 0050](0050-focus-when-a-file-tab-is-shown.md)).
- **Empty scratches are deleted when their last tab closes** in any window: `EditorArea.onFileClosed` reports a file
  whose last tab in the area closed, in whatever way (close, close others, closing the window, after a delete, or a
  move its tab cannot follow; a move followed in place reports nothing); `WindowScratches` deletes it with
  `ScratchFiles.deleteIfEmpty` unless a tab in this window or another one (`ProjectWindows.isOpenElsewhere`) still
  shows the same file (`Files.isSameFile`), also under another name: after a case-only rename on a case-insensitive
  file system the old name is reported while the renamed file is still open, and a symbolic link is the same file.
  Only an empty regular file (size 0, links not followed) whose real parent is inside the real scratches folder is
  deleted; the call never throws. So trying the picker leaves no litter, and a scratch with content stays until the
  user deletes it.
- **No git refresh for scratch-only changes:** scratches are normally in no repository, so the explorer's change
  reports that name only scratches or their folder do not refresh the git status; a manual refresh (no paths) still
  does.

## Consequences
- Scratches work like any other file: every viewer, editing, saving, the explorer's file operations.
- Accepted behaviour, not worth special cases:
  - A scratch `.http` file sees only the environment files in the scratches folder.
  - The HTML preview of a scratch serves the scratches folder (not the project); with scripts on, a page can read the
    other scratches.
  - `.editorconfig` is looked up from the scratch upwards (`~/.sluide`, `~`), not from the project.
  - No git markers, diffs or history for scratches.
  - A repository that holds `~/.sluide/scratches` (e.g. dotfiles versioned at `~`) does not refresh its status for
    changes there; Refresh (F5) or the next other change does.
  - Renames and deletes in one window are not followed by other windows' tabs: their watches close clean tabs, and
    tabs with unsaved changes show the file deleted.
  - Two windows may edit the same scratch: a save in one reloads the other's tab, or, if that has unsaved changes,
    shows the usual changes-on-disk banner there.
  - A project that is `~`, or that contains `~/.sluide`, shows the scratches twice; each node keeps its own watch.
  - A top-level folder that is deleted and recreated is watched again only after Refresh (F5), as the project folder.
- New module `scratches` (`ScratchFiles`, `ScratchType`, `SCRATCH_TYPES`, `ScratchTypePicker`), depending on
  `file-icons` for the picker's icons; `ide` wires it in `WindowScratches`
  ([architecture.md](../architecture.md), [scratch-files.md](../features/scratch-files.md)).
