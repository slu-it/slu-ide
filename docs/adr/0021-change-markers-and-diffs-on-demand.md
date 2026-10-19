# 0021. Change markers in the gutter and diffs on demand

- Status: accepted; partly superseded by [0062](0062-git-concurrency.md) (`diffOf` answers asynchronously, git off the JavaFX thread, the newest answer per file counts)
- Date: 2026-10-14

## Context
Modified files opened as the read-only side-by-side diff ([ADR 0015](0015-git-diff.md)). Since files can be edited
([ADR 0020](0020-editing-and-saving-files.md)), that is in the way: a tracked file is usually modified while it is
worked on, and the diff took the editor's place. In the interim, an open editor was never turned into a diff, so a
file that became modified while open showed no changes at all until it was opened again. We want the editor always,
the changed lines visible in it, and the side-by-side diff when asked for, without losing unsaved text, caret or undo
history.

## Decision
- **Files always open in the editor.** `EditorArea` no longer opens or swaps in diff views by itself. It asks
  `diffOf` when a file is opened, when it changes on disk and on `refreshDiffs()` (the git status changed), and passes
  the answer to `EditableFileView.showChanges(diff)`; `null` clears the markers. Previews pass it to their source.
  Only files shown as text (code, plain text, preview sources: `FileViewers.showsDiffs`) are asked, so images and
  the like cause no git call on the FX thread.
- **Markers in the gutter.** `Gutter` replaces RichTextFX's `LineNumberFactory` in `CodeView`: per paragraph an `HBox`
  with the line number (style class `lineno`, as before), a 4 px `change-marker` region and further columns.
  - `LineMarkers.of(changes, lineCount)` gives each line ADDED, MODIFIED or REMOVED_ABOVE (a removed block marks the
    line after it, the last line at the end), styled `diff-added`, `diff-modified`, `diff-removed-above`. The bars use
    stronger tones of the diff view's colours, in light and dark (`.change-marker.diff-added`; two classes on one node
    work inside `@media`, unlike descendant selectors, [ADR 0009](0009-themes.md)).
  - The area creates a paragraph's graphic again whenever its index changes, so the numbers follow edits. The factory
    is replaced (all visible rows created again) only when the markers, the digit count or a column change.
  - A column hook, the public interface `GutterColumns` (`addGutterColumn((paragraph) -> Node?)`,
    `refreshGutter()`) that code views implement, lets other modules add columns, e.g. run icons for HTTP requests.
    `refreshGutter()` creates the visible rows again: RichTextFX only creates a paragraph's graphic anew through the
    factory, so rows are not refreshed one by one.
- **Markers follow unsaved edits.** The diff describes the file on disk. Each text change becomes a `LineEdit` (whole
  lines inserted or removed leave the neighbouring line alone, e.g. Enter at the end of a line adds one line), and
  `LineMarkers.afterEdit` marks replaced lines modified, inserted ones added and moves the rest. Several changes at
  once (one undo step of a multi-change) are each seen in the text as it was before them.
  - `ChangeMarkers` keeps the edits since the text was last saved or read. A save keeps the markers that followed the
    edits until git's diff of the saved text arrives; the same diff of the same text again (e.g. `refreshDiffs()` while
    edited) changes nothing; a different diff of the same saved text (e.g. after a commit) gets the edits again. Lines
    edited apart stay apart. Only back at the diff's text (all edits undone) are the diff's markers taken as they are.
    Without the edits (more than 1000, or the user kept the unsaved text over a change on disk), the difference between
    the saved and the current text counts as one edit.
  - While the file changed on disk under unsaved edits (the banner offering Reload / Keep mine), a diff given is of
    the file's new text, not of the text the edits started from: it waits until the banner is answered.
  - For files without a diff (clean or untracked), the markers show the unsaved edits; they cannot be clicked.
- **The diff on demand.** `OpenFile` keeps its editor for as long as the tab is open and shows a `DiffFileView` over
  it on demand (`diffView`); the editor is hidden meanwhile, never disposed, so text, caret, scroll position and undo
  history survive. `dirty` and the tab's dot follow the editor.
  - `EditorArea.toggleDiff()` switches; `canToggleDiff` (the selected file has a diff or shows it) enables
    View → Show Changes (Shortcut+Shift+D). Without a diff, nothing happens and the menu item is disabled.
  - The diff view's toolbar gets an Edit button and Escape goes back (`DiffFileView.onBack`, set by the editor area).
    If the diff had the focus, the editor gets it back: where it was in the editor when the diff was shown, or else the
    text (`FileView.focusText()`). A diff going away while the focus is elsewhere leaves the focus there.
  - Show Changes is only enabled for files shown as text that have a diff (not for images, terminals or clean files).
  - The diff shows the saved file against `HEAD`; while the editor has unsaved changes, the right side's title says so
    ("Working Tree (saved)"). It follows changes on disk and the git status, and gives way to the editor once the file
    has no diff anymore (clean, committed).
  - Clicking a marker asks the host (`FileViewHost.showChanges(file, line)`) to show the diff at the change of that
    line (`DiffFileView.jumpToChangeAt`): the change the line is in, else lines removed right above it, else the next.

Rejected:
- **Keeping the automatic diff for modified files:** takes the editor away from the files worked on most.
- **Computing the markers from the unsaved text with our own line diff:** a second diff algorithm next to git's; git
  gives the exact markers after every save, and in between the edits are known exactly.
- **Replacing the editor by the diff in the tab (as before):** the editor would have to be recreated, losing undo
  history and caret, or kept alive outside the tab anyway.
- **A diff of the unsaved text:** the diff stays git's (ADR 0015); the title says that the right side is the file as
  saved.

## Consequences
- Partly supersedes [ADR 0015](0015-git-diff.md) (modified files open as a diff, views swapped in the tab) and the
  interim rule of [ADR 0020](0020-editing-and-saving-files.md).
- Markers of unsaved edits are approximate: a line changed and changed back stays modified until git's diff after the
  next save or until all edits are undone; REMOVED_ABOVE on the last line stands for lines removed after it.
- After a save or a change of the git status, the markers are git's for the saved text plus the edits since, so
  separate edits keep separate markers.
- `diffOf` is only asked for files shown as text.
- Clicking a marker of a line with unsaved edits opens the diff of the saved file, at the change of the same line
  number.
- `diffOf` still runs `git diff` on the FX thread, now also on every save of a modified file (after the change on
  disk is reported).
