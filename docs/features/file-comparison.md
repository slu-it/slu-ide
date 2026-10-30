# File Comparison

**Modules:** `modules/git` (`GitFileComparison`), `modules/file-viewers` (side-by-side view, Swap Sides),
`modules/file-explorer` (Compare Files), wired in `ide` (`dev.sluide.ide.compare`: `WindowFileComparison`,
`FileComparisonTab`, `FileComparison`); decisions in [ADR 0113](../adr/0113-comparing-two-files.md)

Two files side by side in a read-only tab, with the same colours, changed words and change navigation as the git
diffs ([git-diff.md](git-diff.md)).

## Opening
- Select two files in the explorer and choose **Compare Files** in the context menu
  ([file-explorer.md](file-explorer.md)). It is enabled for exactly two regular files; they may lie anywhere the
  explorer shows (project, Scratches), in different repositories or in none.
- The upper file in the tree goes on the left, the lower one on the right, whatever order they were selected in.
- The tab is titled `a.txt ↔ b.txt`; for two files of the same name `name (dirA ↔ dirB)`, with their folders' names.
  Its tooltip, "Comparing <path> and <path>", shows both full paths without telling the sides. Above each side
  stands the file's path relative to the project folder (the full path outside it), with " (saved)" while an editor
  has unsaved changes of the file: the comparison reads the files as saved on disk.
- Syntax highlighting follows the right file's name. Shortcut+W closes the tab.

## The comparison
- Both files are read once when the comparison opens; the tab does not follow later changes. Compare Files on the
  same two files again (either order) selects the tab and reads them again.
- **Swap Sides** on the toolbar flips left and right, also in the title. Comparing again keeps the tab's sides.
- Identical files open a tab without changes.
- Line endings count: a file with CRLF and one with LF differ on every line. Repository settings (`.gitattributes`,
  `core.autocrlf`) do not apply; the global `diff.algorithm` of git does.

## Limits
- The lines come from `git diff --no-index` ([ADR 0044](../adr/0044-git-log-compare.md)), so comparing needs git.
- A binary file (a NUL byte in its first 8000 bytes), a file larger than 5 MB, a file that cannot be read or a
  failing git shows a message ("a.txt and b.txt cannot be compared: one of them is a binary file.") and opens no tab.
  Files of a type that is not shown as text (e.g. images) neither ("b.png cannot be shown as text.").
- A comparison still running when its window closes is dropped.
- Folders cannot be compared.

## API
- `GitFileComparison.compare(left, right): GitFileDiff?` (`git`, Spring singleton, no JavaFX): `Text` with both texts
  and git's hunks (none for identical bytes, without running git), `Binary`, `TooLarge`, or `null` (not a regular
  file, unreadable, git missing or failing). Runs git on the calling thread; files are read bounded to 5 MB and
  compared as temporary copies (`TextDiffs.kt`, shared with `GitLog`).
- `FileExplorerView.compareFiles: ((upper, lower) -> Unit)?`: `null` leaves the item out.
- `DiffFileView.onSwap`: while set, the view shows Swap Sides and calls it; the tab builds a new view for the swapped
  comparison (`FileComparison.swapped()` exchanges texts and each hunk's ranges).
- `WindowFileComparison.compare(upper, lower)` runs the comparison on the "File comparison" thread and opens, or
  refreshes, the tab on the FX thread; messages go to the window's error dialog.
