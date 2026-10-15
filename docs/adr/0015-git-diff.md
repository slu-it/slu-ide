# 0015. Side-by-side diff of modified files from the git command line

- Status: accepted; partly superseded by [0021](0021-change-markers-and-diffs-on-demand.md) (modified files open in the editor with change markers, the diff on demand)
- Date: 2026-10-13

## Context
Opening a file whose git status is modified should show it side by side with its last committed version: removed
lines grey on the left, modified lines blue on both sides, added lines green on the right. Added and unversioned
files are new as a whole and open normally. The tab should switch between diff and normal view when the status
changes (an edit, a commit) while it is open.

Options considered for the diff:
- **The `git` command line**: `git diff -U0 HEAD -- <file>` gives the changed line ranges in its hunk headers, and
  `git cat-file --filters HEAD:<file>` the original with the same line endings and filters as a checkout. Same tool
  and same answers as `git status` (ADR 0013).
- **A diff library** (e.g. java-diff-utils) on the two texts: one git call fewer, but a new dependency and a second
  diff algorithm that can disagree with git's (e.g. with `diff.algorithm` set).

## Decision
- `GitCli.headContent` and `GitCli.diffToHead` run the two commands; `GitDiffParser` reads only the `@@ -a,b +c,d @@`
  headers into 0-based line ranges (`GitHunk`). `GitStatusService.diff(file)` combines them for the followed
  repository. The base is `HEAD`, so staged and unstaged changes both show.
- The diff runs as `git --no-optional-locks --literal-pathspecs diff --no-color --no-ext-diff --no-textconv -U0 HEAD
  -- <file>`. `--literal-pathspecs` makes the file name a name, not a pattern, so `star*.txt` never diffs `starX.txt`
  too. The other options keep git's output plain hunk headers whatever the user configured (colours, external diff
  tools, textconv filters) and, as for the status, leave `.git/index` alone.
- The calls run synchronously on the JavaFX thread: they concern one file and take milliseconds; each has a 3 s
  timeout. A diff opened while git hangs would block for that long.
- As for the markers, the viewers and the editor do not depend on `git`: `file-viewers` has a generic `TextDiff`
  (`LineChange`s, kind removed/added/modified) and `FileViewers.openDiff`, `editor` asks `EditorArea.diffOf`, and
  `MainWindow` maps `GitDiff` to `TextDiff` for files whose status is modified (`GitDiffs.kt`).
- The view (`SideBySideDiffView`) is two read-only RichTextFX code areas. Filler rows pad the shorter side of each
  change, so both sides have the same rows, line by line aligned, and their scroll positions are kept equal (the
  position of the side scrolled last is copied once it has settled; a bidirectional binding copied the intermediate
  positions of a side's layout back and forth). Line numbers come from our own paragraph graphic factory, empty on
  filler rows. Highlighting reuses the file's code viewer `SyntaxHighlighter`, or for preview viewers (Markdown, HTML,
  SVG, CSV, notebooks) the highlighter of their source text; plain text files get none, other viewers (e.g. images) no
  diff.
- An open file tab keeps a stable content node and swaps its `FileView` inside it. `diffOf` is asked when a file is
  opened, when it changes on disk, and for all open files when the git status snapshot changes (`refreshDiffs`).

## Consequences
- The diff is exactly git's, including attributes and the configured diff algorithm.
- Changes are whole lines, with no connecting shapes between the sides (filler rows instead). The changed words
  within modified lines are found by Thoreau itself ([ADR 0019](0019-word-diff.md)).
- Switching between diff and normal view resets the scroll position.
- The diff stays read-only like every other view; there is no reverting of changes from it.
