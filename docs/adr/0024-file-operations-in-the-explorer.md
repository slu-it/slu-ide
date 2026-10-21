# 0024. File operations in the explorer

- Status: accepted; partly superseded by [0073](0073-explorer-multi-selection-and-moving.md) (moving to another
  folder, and deleting several entries)
- Date: 2026-10-14

## Context
The file explorer could only show and open files. Users want to create, rename and delete files and folders from it
without a terminal. These operations change the disk for good, may hit names the file system refuses or treats as
equal (case-insensitive file systems on macOS), and affect files that are open in tabs, which belong to the editor
module, not the explorer.

## Decision
- The operations live in `FileOperations` in `file-explorer`, plain Kotlin on `java.nio.file`, unit-tested without
  a toolkit; the explorer adds the context menu, the dialogs and the keys.
- **Names** are single entries of a folder: not empty, no `/` or `\`, no NUL, not `.` or `..`, no leading or trailing
  whitespace, storable by the file system, and not taken (`Files.exists` without following links, so a dangling link
  counts and a name differing only in case counts on case-insensitive file systems). No nested paths such as `a/b`: one dialog creates one entry.
  The name dialog checks while typing; OK stays disabled while there is a problem, which is shown below the field.
- **Rename** stays within the folder (`Files.move` without replacing; on Unix that is a check before the rename, so an
  entry created in between can still be replaced). Moving to another folder came with
  [ADR 0073](0073-explorer-multi-selection-and-moving.md), by drag and drop; paths in the name stay out of scope.
  Changing only the case of a name works on case-insensitive file systems over a temporary name, since there
  `Files.move` sees the target as the file itself and does nothing. If the second step fails, the entry is moved
  back, and if that fails too, the error says where it was left.
- **Delete** is permanent, a folder with everything in it, after a confirmation that names the entry, says that it
  cannot be undone and counts the files with unsaved changes that would be lost. Cancel is its default button, so
  Enter keeps everything. If the entry changed its kind while asking (e.g. a file became a folder), nothing is
  deleted. Deleting stops at the first entry that cannot be deleted, which can leave a folder partly deleted. Links are deleted themselves, never
  followed. There is no trash: `java.awt.Desktop.moveToTrash` needs AWT next to JavaFX and is not supported on Linux
  desktops, and implementing the freedesktop.org trash and the macOS one ourselves is not worth it yet.
- **Threads:** creating and renaming are single file system calls and run on the JavaFX thread. Deleting can take
  long (`node_modules`), so it runs on a virtual thread; the explorer refreshes, selects, reports and shows errors back
  on the JavaFX thread (`runInBackground` and `dispatch`, replaced in tests). Meanwhile the entry and everything in it
  cannot be renamed or deleted again.
- The explorer shows the result at once (re-reading the folder instead of waiting for its watch), selects the new or
  renamed entry and opens a new file through `onOpenFile`. The opened folder itself cannot be renamed or deleted.
- **Open tabs** are none of the explorer's business: it reports `onMoved(from, to)` and `onDeleted(path, unsaved)`
  and asks `unsavedUnder(path)` for the confirmation. The window connects them to the editor area:
  - `EditorArea.moved` runs right after the rename, on the JavaFX thread. The watch of a renamed file would report
    its old path as deleted (closing a clean tab, marking an edited one deleted); the watches of the files in a
    renamed folder follow the folder (inotify watches the folder itself) and report nothing for the old paths, so
    without `moved` their tabs would stay at paths that no longer exist. Every tab at or below the old path takes
    the new path: title, tooltip, watch and diff follow. Its view follows the file (`FileView.moved`: code views and
    previews), so text, caret, undo history and unsaved changes stay; if the view cannot follow, or another viewer
    shows the new name (`notes.txt` to `notes.md`), the file opens anew in the same tab position. A tab with unsaved
    changes always keeps its view, so nothing typed is lost, also when the new name would get another viewer. If a
    tab is open at the new path already (one with unsaved changes whose file was deleted), it reads the file now
    there at once, so it shows the difference instead of a save overwriting it unseen.
  - `unsavedUnder` is `EditorArea.dirtyUnder`, and `onDeleted` is `EditorArea.closeUnder`. The explorer passes the
    files the confirmation counted as lost (`unsaved`); `closeUnder` closes the tabs at or below the deleted path
    without asking again if they are clean or among those, and leaves tabs edited after the confirmation (while the
    delete ran in the background) open with their changes and the "deleted" banner.

## Consequences
- A deleted file is gone; the confirmation is the only safety net. A trash can be added later behind `delete`.
- Renaming or deleting from the explorer and outside the IDE look the same to the rest of the IDE (watches, git
  status); only the callbacks let tabs follow a rename instead of seeing the file vanish. A file renamed outside the
  IDE still closes its clean tab and leaves an edited one with a "deleted" banner; the tabs of files in a folder
  renamed outside the IDE notice nothing and keep the old paths, where saving fails because the folder is gone.
- A tab with unsaved changes whose file is renamed to a name another viewer shows (which reopens a clean tab) keeps
  the highlighting and comment syntax of its old name until it is closed and opened again. Its indentation is taken
  again for the new path (`.editorconfig`, else the saved text; ADR 0023), like that of every code view that follows
  its file.
- Files edited after the delete confirmation keep their tabs; their changes are lost only if the user discards them
  when closing.
- Added to [../index.md](../index.md).
