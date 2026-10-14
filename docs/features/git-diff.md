# Git Diff

**Modules:** `modules/git` (diff), `modules/file-viewers` (view), `modules/editor` (tabs), wired in `MainWindow`;
decision in [ADR 0015](../adr/0015-git-diff.md)

- Opening a file whose git status is modified (blue in the explorer, [git-status.md](git-status.md)) shows it side by
  side in its tab: on the left as of the last commit (`HEAD`), on the right as it is now ("Working Tree").
  Staged and unstaged changes both count.
- Colours, on the lines and their numbers, light and dark:
  - grey: removed lines, left side only;
  - blue: modified lines, both sides;
  - green: added lines, right side only.

  Within modified lines, the words that changed get a stronger blue on both sides; a line added within a modified
  block is stronger blue as a whole ([ADR 0019](../adr/0019-word-diff.md)). The other side shows empty rows there,
  so the sides stay aligned; both scroll together. Line numbers are those of each version. Code is highlighted like
  in the normal view.
- A toolbar above both sides jumps to the first, previous, next and last change (arrow buttons; F7 and Shift+F7 for
  next and previous while the diff has focus). A change jumped to is shown a few rows below the top, with the caret
  on its first row on both sides. Next and previous are seen from the caret, so clicking a row on either side starts
  from there. The toolbar counts the changes and tells which one the caret is in ("Change 2 of 5").
- Added and unversioned files open normally, they are new as a whole. Clean files open normally too.
- The tab follows the status: it turns into a diff when the file becomes modified (an edit, in SluIde's terminal or
  elsewhere) and back into the normal view when it is clean again (a commit, a checkout); a diff updates when the file
  changes. The scroll position resets when the view switches.
- Files with a text, code or preview viewer (Markdown, HTML, SVG, CSV, notebooks) are shown as a diff of their text,
  highlighted like their source; others (e.g. images) open normally.
- Not yet implemented: diffs against the index or other commits, reverting changes.
