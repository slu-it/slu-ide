# 0113. Comparing two files: git's `--no-index` diff on snapshots, the upper file on the left

- Status: accepted; amends [0073](0073-explorer-multi-selection-and-moving.md) (a menu item for exactly two targets)
  and [0044](0044-git-log-compare.md) (its `--no-index` text diff is shared); see [0015](0015-git-diff.md),
  [0019](0019-word-diff.md)
- Date: 2026-10-30

## Context
Every diff so far compared a file with a git version of it: Show Changes against `HEAD`
([ADR 0021](0021-change-markers-and-diffs-on-demand.md)), the diffs of a commit ([ADR 0038](0038-changed-files-and-commit-diffs.md))
and Compare with Local ([ADR 0044](0044-git-log-compare.md)). Comparing two arbitrary files (two versions of a
config, a file and its copy, a scratch and a project file) needed a terminal. The explorer can select several entries
([ADR 0073](0073-explorer-multi-selection-and-moving.md)), and the side-by-side view, the word diff and git's
`--no-index` diff of two texts already exist. Open questions: where the line diff comes from, which file goes left,
whether the tab follows changes on disk, and what the right side is.

## Decision
- **Entry point: "Compare Files"** in the explorer's context menu, after Open and the file actions, enabled for exactly
  two regular files (anywhere: project, Scratches, other repositories or none). It is the only item that needs exactly
  two targets; the explorer only passes the files on (`FileExplorerView.compareFiles`, hidden while `null`). No
  file chooser, tab-menu or clipboard comparison for now.
- **The upper file in the tree goes left.** The explorer does not keep a reliable selection order, and tree order gives
  the same result however the files were clicked. A **Swap Sides** button on the diff's toolbar
  (`DiffFileView.onSwap`, shown while set) flips them.
- **Swapping inverts the diff in memory**: the texts trade places and each hunk's ranges are exchanged, without running
  git again. The `-U0` convention for empty ranges is symmetric, so the inverted hunks are a valid diff; they can
  differ from what git would choose for the other direction, which is acceptable for a view.
- **Lines from git, as everywhere** ([ADR 0015](0015-git-diff.md): no diff library): `GitFileComparison` in `git` reads
  both files in the JVM (at most 5 MB each, read bounded so a growing file cannot get past the limit), stops at a NUL
  byte in the first 8000 bytes (binary), skips git for identical bytes and otherwise runs the `--no-index` diff of
  [ADR 0044](0044-git-log-compare.md) on temporary copies. That text diff moved out of `GitLog` into `TextDiffs.kt`
  and is shared. No repository's attributes or configuration apply, so line endings count; the user's global
  `diff.algorithm` does, as no algorithm is passed.
- **A read-only snapshot tab.** Both texts are read once and kept; the tab does not watch the files. Comparing the same
  pair again (either order) selects its tab and reads both files again, keeping the tab's sides. Both sides are fixed
  texts: the right one is not tied to the file, since a re-read right side would no longer fit the left one's hunks.
- **Binary, too large or unreadable files** (or git missing) show a message instead of a tab.
- `ide` (`dev.sluide.ide.compare`) runs the comparison on a background thread and builds the tab; no module depends
  on another for it.

## Consequences
- Any two text files can be compared from the explorer, with the same view, word diff and change navigation as git
  diffs, and without a repository.
- The tab can go stale when a file changes; running Compare Files again refreshes it.
- Comparing needs git, like every other diff. Without git the item stays and says the files cannot be compared.
- Folders cannot be compared; a folder diff would be a feature of its own.
