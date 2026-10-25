# 0073. Multi-selection and moving entries in the explorer

- Status: accepted; its rejection of Cut/Copy/Paste is superseded by
  [0092](0092-explorer-copy-cut-paste.md) (files come in and go out over the clipboard)
- Date: 2026-10-21

## Context
The file explorer ([ADR 0024](0024-file-operations-in-the-explorer.md)) worked on one entry at a time: one selected
row, a context menu for the clicked row, delete one entry per confirmation. Rearranging a project (moving files into
another folder, into or out of Scratches) needed a terminal. Users expect what other IDEs offer: select several
entries, act on all of them, and drag them onto a folder. Moving changes the disk for good, can meet names that are
taken in the target, and affects files open in tabs, which belong to the editor area.

## Decision
- **Multi-selection:** the tree uses `SelectionMode.MULTIPLE` (Shift for ranges, Shortcut+click to add or remove,
  Shortcut+A). The context menu is for the selection if the clicked row is part of it, else for that row alone; the
  context-menu key opens it for the selection, the empty area for the project folder. `menuState`
  (`actions/ExplorerMenu.kt`) decides what is enabled: Open for at least one regular file (opening every one, in row
  order); New File…, New Folder… and the folder actions for exactly one folder (also a top-level one) or the empty
  area while a project is open, no longer beside a clicked file; Rename for exactly one entry that can be changed;
  Delete when all targets can be changed; Show History for exactly one file. Enter opens all selected files, F2
  renames only a single entry.
- **Delete of several entries:** entries at or below another one are left out. One confirmation names one entry, or
  says "Delete N entries?" and lists the first ten names, and counts the files with unsaved changes over all of them.
  After it the kinds are checked again; entries that changed (a file became a folder) are not deleted and reported in
  one error. The others are deleted in one background run, in row order, stopping at the first failure; `onDeleted`
  is called for every entry deleted completely. Meanwhile the entries, everything in them and the folders holding them
  cannot be renamed, deleted or moved (`canChange`), so no half-deleted tree is moved or renamed, and nothing is moved
  into them. If the selection lay in the deleted entries, the first remaining row after them (else before them, else
  their folder) is selected at once.
- **Moving by drag and drop** (`tree/TreeDragAndDrop.kt`, `actions/ExplorerMoves.kt`, `FileOperations.move`):
  - **A plain mouse drag within the tree, not the platform's drag and drop** (`startDragAndDrop`). Event filters on
    the tree follow a press on a row; moving 5 px with the button down starts the drag, the release ends it. The row
    under the pointer is found by position among the shown cells, since JavaFX delivers the drag's events to the
    pressed row. The pressed row's icon and name follow the pointer in a `Popup`, offset so it is never under it;
    the cursor is a closed hand over a target. Escape (handled on that popup too, which gets the window's key events
    while it shows), any mouse press (also a primary one after a release that never reached the tree), losing the
    focus (the drag takes it), another tree content (`open`), or releasing elsewhere cancels. The gesture's events
    are consumed, so a drag is no click on its release (no selection change, no double-click opening), and the rest
    of a press that cancelled it, its context menu included, is kept from the rows. The move flow also refuses
    entries no longer shown.
  - Why not the platform drag and drop: on Linux it broke the confirmation. JavaFX's GTK glass (`glass_window.cpp`)
    grabs the pointer for the main window when the mouse moves with a button down (`grab_mouse_drag_focus`,
    `sm_mouse_drag_window`) and releases the grab only on a button release in that window; GTK's drag and drop
    swallowed that release, so the grab stayed, and the modal Move dialog opened after the drop got no mouse clicks
    (they went to the disabled main window) until it was closed by keyboard. A `runLater` from the drop handler also
    still ran inside GTK's drag loop. Nothing needs the platform's drag and drop: entries are only dragged within the
    tree, never in from or out to other applications or windows.
  - Sources: the selection if the pressed row is selected, else that row; not top-level folders, not entries being
    deleted or holding one being deleted. Entries inside another dragged folder go along with it. A drag that ends
    without a move (cancelled, released elsewhere, the question cancelled or every name taken) selects them again.
  - The press keeps a selection of several entries, so it can be dragged: JavaFX's cell behaviour selects only the
    pressed row on the release if the row was selected and the mouse not dragged. The filters keep moves below the
    threshold from the row, so a press and release that stays a click still selects only that row.
  - Targets: a folder row, or the empty area below the rows for the project folder; any folder of any top-level root,
    so entries move into and out of Scratches. Not a file row, not the scroll bars, not a dragged folder or a folder
    inside one, not the folder all dragged entries are in already (`canDrop`), and not a folder being deleted or
    inside one. The target row gets `:drop-target` (the project row for the empty area); a collapsed folder under the
    pointer expands after 700 ms, and the tree scrolls while the pointer is within a row of its top or bottom edge,
    taking the row that scrolls under the pointer as the target.
  - Every drop is confirmed: the release runs the move with `Platform.runLater`, so the confirmation, which waits in
    an event loop of its own, opens after the release has been handled. For one entry the question says where from
    and where to (folders with their top-level folder, e.g. "From “Scratches/notes” to “project/src”.").
  - Names taken in the target are never replaced: those entries are skipped, after the question says so ("Move the
    others"); if all clash, an error is shown instead. Each move checks again (`Files.move` without
    `REPLACE_EXISTING`, a name taken while asking stops with an error). A delete started while asking stops the move
    before anything moves, with an error.
  - The moves run in order on the JavaFX thread (rename system calls, like a rename), stop at the first failure and
    show it; earlier moves stay. Each is reported through `onMoved(from, to)`, so open tabs follow as for a rename
    (`EditorArea.moved` handles any old and new path). Afterwards the source folders and the target are read again,
    the target is expanded and the moved entries are selected, a moved folder expanded if it was.
  - Moves are renames only. A symbolic link is moved itself. Nothing is moved to another file store (drive), neither
    a file nor a folder: the stores of the entry's folder and the target are compared first, and the error says it
    cannot be moved to another drive (if a store cannot be read, the move is tried, and `Files.move` refusing a folder
    with content gives the same error). `Files.move` would copy and delete across stores, which could take long on
    the JavaFX thread and leave half a copy behind; there is no copy fallback.
- **Rejected:** Cut/Copy/Paste of entries (more state to explain, clipboard semantics across windows; came later
  with [ADR 0092](0092-explorer-copy-cut-paste.md)), a "Move to…"
  dialog with a folder chooser (slower than dropping on the visible tree), and replacing clashing entries after a
  question (one wrong answer destroys a file with no trash or undo).

## Consequences
- Moving can only be undone by moving back; there is no undo.
- A drop always asks, also for one entry, so a slip of the mouse moves nothing.
- Moving between drives (e.g. the project on a USB drive and Scratches in the home folder) is refused, for files and
  folders alike.
- A file moved into the scratches folder counts as a scratch ([scratch-files.md](../features/scratch-files.md)).
- Drag and drop with the OS (dropping files from a file manager) stays out of scope.
- Listed in [../index.md](../index.md).
