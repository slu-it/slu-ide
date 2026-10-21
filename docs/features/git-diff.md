# Git Diff

**Modules:** `modules/git` (diff), `modules/file-viewers` (markers and view), `modules/editor-area` (tabs), wired in
`MainWindow`; decisions in [ADR 0015](../adr/0015-git-diff.md) and
[ADR 0021](../adr/0021-change-markers-and-diffs-on-demand.md)

- Files always open in the editor. For a file whose git status is modified (blue in the explorer,
  [git-status.md](git-status.md)), the lines changed against the last commit (`HEAD`) are marked in the gutter, between
  the line numbers and the text: green for added lines, blue for modified ones, a grey tick at the top of a line where
  lines were removed above it (on the last line for lines removed at the end). Staged and unstaged changes both count.
  Added and unversioned files are new as a whole and have no markers, clean files neither. Git runs in the
  background: the markers appear, or change, once git has answered, and until then the old ones stay
  ([ADR 0062](../adr/0062-git-concurrency.md)).
- The markers move along with unsaved edits: an edited line is marked modified, a new line added, lines below move
  with them. Undoing all edits gives git's markers back. After a save, the markers stay as they were until git's diff
  of the saved file arrives; when the git status changes while the file is edited, git's new markers get the unsaved
  edits again, so lines edited apart keep separate markers. While the file changed on disk under unsaved edits (the
  Reload / Keep mine banner), the markers wait for the answer. In files without a diff (clean or untracked), the
  markers show the unsaved edits until they are saved; clicking them does nothing.
- **View → Show Changes** (Ctrl+Shift+D, Cmd+Shift+D on macOS) shows the file side by side in its tab: on the left as
  of `HEAD`, on the right as saved ("Working Tree"; "Working Tree (saved)" while the editor has unsaved changes).
  It is disabled for files without a diff and for other tabs (e.g. terminals). The same command, the **Edit** button
  on the diff's toolbar or Escape go back to the editor, which kept its unsaved changes, caret, scroll position and
  undo history. If the diff had the focus, the editor gets it: where it was before, or else its text; a diff that goes
  away while the focus is elsewhere (e.g. after a commit) leaves the focus there.
- Clicking a change marker opens the diff at that change: the change the line is in, or the lines removed above it.
- Colours of the diff, on the lines and their numbers, light and dark:
  - grey: removed lines, left side only;
  - blue: modified lines, both sides;
  - green: added lines, right side only.

  Within modified lines, the words that changed get a stronger blue on both sides; a line added within a modified
  block is stronger blue as a whole ([ADR 0019](../adr/0019-word-diff.md)). The other side shows empty rows there,
  so the sides stay aligned; both scroll together. Line numbers are those of each version. Code is highlighted like
  in the editor. The markers in the gutter use stronger tones of the same colours.
- A toolbar above both sides jumps to the first, previous, next and last change (arrow buttons; F7 and Shift+F7 for
  next and previous while the diff has focus). A change jumped to is shown a few rows below the top, with the caret
  on its first row on both sides. Next and previous are seen from the caret, so clicking a row on either side starts
  from there. The toolbar counts the changes and tells which one the caret is in ("Change 2 of 5").
- Markers and a shown diff update when the file changes on disk and when the git status changes (a commit, a
  checkout). A shown diff turns back into the editor when the file has no diff anymore.
- Files with a text, code or preview viewer (Markdown, HTML, SVG, CSV, cookie jars, notebooks, Mermaid, PEM;
  `.env` files with their values unmasked) get markers and a diff of their
  text, highlighted like their source; others (e.g. images) have neither, and git is not asked for them.
- The same side-by-side view shows what a commit changed in a file, opened from the git log's changed files
  ([git-log.md](git-log.md)): read-only in a tab of its own, without the Edit button, its right side the file as the
  commit left it (`FileViewers.openDiff(name, current, diff)`, highlighted by the file's name).
- Structure: `LineMarkers`, `LineEdit` and `ChangeMarkers` (`LineMarkers.kt`, plain logic), `Gutter` (rows of the
  gutter; the column hook `GutterColumns` is implemented by code views), `EditableFileView.showChanges`,
  `FileView.focusText`, `DiffFileView` (`onBack`, `jumpToChangeAt`), `FileViewHost.showChanges(file, line)`,
  `FileViewers.showsDiffs`, `EditorArea.toggleDiff()` and `canToggleDiff`, `OpenFile.diffView`; diff tabs of their
  own get the editor font through `EditorTab.usesEditorFont`.
- Not yet implemented: diffs of the working tree against the index or other commits (a commit's own changes are
  shown from the git log), diffs of the unsaved text, reverting changes.
