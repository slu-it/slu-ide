# 0092. Copy, cut and paste in the explorer

- Status: accepted
- Date: 2026-10-25

## Context
The explorer can select several entries, delete them and move them by dragging
([ADR 0073](0073-explorer-multi-selection-and-moving.md)), but it cannot copy anything. ADR 0073 rejected Cut/Copy/Paste
("more state to explain, clipboard semantics across windows") and kept files from other applications out. Users expect
Shortcut+C / Shortcut+V to copy entries, a modifier on a drag to copy instead of move, automatic names on clashes, Cut,
and copy and paste with the Finder (or another file manager) in both directions. Copies can be large (`node_modules`),
meet taken names, include links and executable files, and come from files with unsaved changes in tabs.

## Decision
- **Supersedes ADR 0073's rejection of Cut/Copy/Paste**, and its "nothing from outside" for the clipboard. Drag and
  drop with other applications stays out of scope.
- **The system clipboard is the transport** (`Clipboard.getSystemClipboard()`, `ExplorerClipboard.kt`): Copy
  and Cut put `DataFormat.FILES` and the absolute paths as `PLAIN_TEXT`, one per line. That gives the Finder (and file
  managers on Linux and Windows) both directions and paste across project windows without a channel of our own.
  Clipboard access stays on the JavaFX thread behind a small seam (`ClipboardFiles`), faked in unit tests.
  `ExplorerClipboard` (with the cut mark) is a singleton bean injected into the window-scoped `FileExplorerView`, so
  all windows share it; tests construct their own.
- **Keys:** Shortcut+C copies, Shortcut+X cuts, Shortcut+V pastes, Escape cancels a cut (a running drag takes Escape
  first). They are handled by the explorer's key handler (`ExplorerKeys.kt`) while it has the focus. The context menu
  has Cut / Copy / Paste after Open and the file actions, before New File…, showing the keys. A control's context
  menu puts the accelerators of its items into the whole scene (JavaFX's `ControlAcceleratorSupport`), so a real
  accelerator would take Shortcut+C from the editor and the terminal; the items carry a `KeyCombination` that shows
  the keys and matches nothing (`ShownKeys`).
- **Copy** takes the selection, entries inside another selected folder left out; also top-level folders (e.g. to
  paste the project in the Finder). It ends a pending cut.
- **Paste target:** the single selected folder, the folder of the single selected file, the project folder when
  nothing is selected (in the menu: the empty area); nothing for several selected entries, for a folder being deleted
  or without files on the clipboard (Paste disabled; the menu asks the same resolution as the paste,
  `ExplorerCopies.canPaste`). Pasting into Scratches works like any folder.
- **Names:** the entry's own name if free, else the first free of `-copy`, `-copy2`, `-copy3`, … before the first
  extension; a leading dot does not start one, a folder has none (`notes.txt` → `notes-copy.txt`, `archive.tar.gz` →
  `archive-copy.tar.gz`, `.env` → `.env-copy`, `.env.local` → `.env-copy.local`, `Makefile` → `Makefile-copy`, folder
  `my.dir` → `my.dir-copy`). A base name ending in `-copy` or `-copyN` (N ≥ 2, no leading zero) is a copy already and
  counts on from `-copy2`: copying `notes-copy.txt` next to itself gives `notes-copy2.txt`. A name counts as taken in
  any case (no copy differs from an entry only in case, which would clash on case-insensitive file systems) and as a
  dangling link. Pasting into the entry's own folder is allowed and gets a suffix; entries of the same name from
  several folders get suffixes in clipboard order.
- **Copying** (`FileOperations.copy`): a recursive walk like delete's, links not followed but copied as links,
  `COPY_ATTRIBUTES` (permissions such as the executable bit, modification times); folders get their permissions and
  times after their content, so read-only folders can be filled. The top-level entry is created exclusively (the file
  system refuses an existing name), and a name taken meanwhile makes it try the next one: nothing is ever replaced.
  Copies across drives are fine (unlike moves). A folder into itself or a folder inside it is refused before anything
  is copied, compared as real paths, so also a folder reached through a link inside it (`current` → `releases/v1`
  inside `releases`) or another spelling of it (`/var` and `/private/var` on macOS); as a backstop the walk never
  descends into the copy it is making. The same real-path check refuses moving a folder into itself.
- **Threads and outcome:** copies run in the background like delete (`runInBackground`, then `dispatch` back to the
  JavaFX thread), in clipboard order; sources gone from the disk are reported and the others copied; the first failure
  stops the rest and is shown, what was copied stays. Afterwards the target is read again and expanded, the copies
  are selected. Nothing is locked while copying.
- **Unsaved changes:** copies take what is on disk. If a copied file (also inside a copied folder) has unsaved changes,
  one dialog names the files and says so (Copy / Cancel, Cancel the default). It is the only dialog of a paste; for a
  drag copy it is folded into the drop question.
- **Cut** follows `canChange` (no top-level folders, nothing being deleted or holding an entry being deleted). It puts
  the same clipboard content and sets an app-wide cut mark (the cut paths); their rows are dimmed (`:cut`). A paste
  moves only while the clipboard's files equal the mark (as sets); any other content drops the mark silently, and the
  paste copies. A cut-paste moves without a confirmation but keeps the clash question of drag moves ("Move the others"
  / Cancel, all taken: an error, never replaced), through the same move flow (`ExplorerMoves.move(paths, folder)`, for
  sources that need not be shown: in a collapsed folder or another window); cut entries gone from the disk are
  reported and the others moved. After a paste that moved something the
  mark and the clipboard are cleared; after Cancel, every name taken or a paste into the entries' own folder (nothing
  to do, no error) they stay cut. Pasting in another project window moves too; only the pasting window reports
  `onMoved` (tabs follow there; the other window sees changes on disk, as for any outside move). Pasting cut entries in
  the Finder copies them (its own rule; Opt+Cmd+V moves). Escape in the explorer cancels a pending cut: the mark is
  dropped, and the clipboard cleared if it still holds the cut entries.
- **No clipboard events:** JavaFX reports no clipboard changes, so the mark is checked again when the explorer gains
  the focus, when its window gains the focus, when the context menu opens and before every paste.
- **Drag copy** (`TreeDragAndDrop`, `isCopyModifier`): holding Option (Alt) on macOS, Ctrl on Windows and Linux at the drop copies
  instead of moving. The modifier is read at the release and may change during the drag, as in the Finder; while it is
  held the drag image shows a round green `+` badge (`.explorer-drag-copy`), separate from the accent-coloured `+N`
  count; Alt and Control key events are tracked during the drag, so the mode, the badge and the drop target change
  without a mouse move. A copy drop also accepts the folder the entries are in already (they get copy names). It is confirmed like a move: "Copy N entries to “folder”?", listing the entries that get another name
  ("“a.txt” → “a-copy.txt”") and the files with unsaved changes, Copy / Cancel, Cancel the default
  (`ExplorerCopies.copyDrop`, `copyDropQuestion`). On Windows and Linux a Ctrl+press stays multi-selection, so Ctrl
  must be pressed after the drag has started.
- Project data (argument histories of runs, ADR 0091) is not carried over to copies; git reacts through the watcher.
- **Rejected:** a clipboard of our own inside the app (no Finder interop, no paste across applications), drag and drop
  in from or out to other applications (the platform drag and drop broke dialogs on Linux, ADR 0073), replacing
  entries on a clash, a dialog for every paste, and real accelerators on the menu items.

## Consequences
- Entries copied in Thoreau paste in the Finder and vice versa; on Linux this depends on the file manager reading the
  `text/uri-list` JavaFX puts for `FILES` (GNOME Files also wants `x-special/gnome-copied-files`).
- A cut is invisible to other applications: they see a copy on the clipboard.
- A cut mark can stay dimmed until the next focus change after another application changed the clipboard.
- A failed copy can leave a partial copy of a folder; it is not cleaned up.
- Copies can only be undone by deleting them.
- Listed in [../index.md](../index.md).
