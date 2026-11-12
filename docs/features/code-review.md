# Review

**Modules:** `modules/code-review` (`Reviews`, the review window, `ReviewDocument`, `ReviewDiffList`),
`modules/git` (`GitReviews`), `modules/git-log` (entry points), wired in `ide` (`WindowReviews`, `GitReviewSource`);
decisions in [ADR 0168](../adr/0168-review-window.md)

A local review of a branch, one or more commits, or the uncommitted changes, laid out like a pull request on GitHub:
all changed files in one long scroll beside a filterable file tree, without pushing or creating a pull request.

## Opening
- **Review Branch** for the current branch: the button left of Push, Pull, Fetch and Refresh in the git log's header
  (set apart by a gap), or Git → Review Branch (Shortcut+Shift+R, Cmd+Shift+R on macOS). Disabled without a
  repository.
- **Review Branch** in the branches pane's context menu and the per-branch submenus of the commit table, for local
  and remote branches (not tags); the branch need not be checked out.
- **Review Commit** / **Review Commits** in the commit table's context menu. Several selected commits are reviewed
  from the oldest one's parent to the newest one, every commit in between included; when the newest does not descend
  from the oldest, the window says "The selected commits are not on one line of history."
- Every review opens a window of its own, large and centred on the IDE window's screen, each further one a little
  offset. Its title is `Review – <scope> – <project>`; Shortcut+W closes it, and closing the project window closes its
  reviews.

## Scope and pickers
- A branch is reviewed against its base like a pull request: from where it left the base (the merge base) to its
  last commit, so changes merged in from the base do not show. The base is the remote's default branch
  (`origin/main` and the like; first that of the remote the reviewed branch tracks, or is on for a remote branch, then
  `origin`'s), else the local `main` or `master`; **into: <base> ▾** on the toolbar picks another branch. Without any
  default branch the review asks to choose a base.
- The **commit picker** offers "All changes", the single commits (newest first) and, while the reviewed branch is
  checked out and has them, **Uncommitted changes** (untracked files included) on top. A click picks one entry,
  Shift+click a range. A merge commit alone is shown against its first parent. A single-commit review has no picker.
- Empty scopes say so ("No changes between origin/main and feature-x."); git failures show "Could not read the
  review: …".

## Files
- The tree on the left lists the changed files, folders first, single-child folder chains compacted
  (`src/main/kotlin`), names coloured as added, modified or deleted (deleted ones struck through), renamed files with
  their old path in the tooltip, viewed files with a check mark. "x / y viewed" stands above it.
- The **filter** field (Shortcut+P) narrows the tree and the diff to the files whose path contains the text, ignoring
  case. Escape clears it, Down moves into the tree, Enter jumps to the first match.
- Clicking a file scrolls the diff to it; scrolling the diff selects the file at its top in the tree.

## Diff
- **Split** (the default) or **Unified**, toggled at the right end of the toolbar; **Hide whitespace** next to them.
  Both choices are remembered for the next review.
- Each file starts with a header: collapse chevron, path (`old → new` for renames), the counts of added and removed
  lines, and **Viewed**, which collapses the file until unchecked (for the window's lifetime; a file that changes on
  Reload is unchecked again). The current file's header stays at the top while scrolling.
- Changes show with 3 lines of context. Between them a gap row with the hunk header expands 20 lines up or down, or
  all of the gap.
- Colours are those of the editor's diff: removed grey, modified blue, added green, changed words darker. Unified view
  shows a modified block as its old lines, then its new ones, told apart by − and +. Long lines wrap. Syntax colours
  and the font are the editor's.
- Large files (over 500 changed lines) and lock or minified files (`package-lock.json`, `yarn.lock`,
  `pnpm-lock.yaml`, `gradle.lockfile`, `Cargo.lock`, `poetry.lock`, `go.sum`, `*.min.js`, `*.min.css`) start
  collapsed with **Load diff (N lines)**. Binary files, files over 5 MB and submodules show a notice instead of
  lines, and so does a file without changed lines: "Renamed without changes", "Copied without changes", "Only
  whitespace changes" (with Hide whitespace) or "No content changes" (e.g. only its mode changed).
- F7 / Shift+F7 move to the next or previous change, Alt+Down / Alt+Up (Option on macOS) to the next or previous file.

## Lines
- Click a line number to select the line, Shift+click to extend the selection within the same file and side (split
  view: left numbers the old side, right numbers the new side). Shortcut+C copies the lines without − and +; Escape
  clears the selection.
- The context menu of selected lines: Copy Lines, Open in Editor (the file in the working tree at that line; disabled
  when the file is not there), Copy Path. A file header's: Open in Editor, Show History, Copy Path, Mark as Viewed.

## Find
- Shortcut+F opens a find bar above the diff: literal text, ignoring case unless Match Case is on, in all rows shown
  of the files that are not collapsed. It counts the matches ("3 / 17"), Enter / Shift+Enter or F3 / Shift+F3 move
  between them, Escape closes the bar.

## Refresh
- When the reviewed commits or, for reviews reaching the working tree, the uncommitted changes change (a commit, an
  amend, a fetch moving the base, an edit), a bar says "The reviewed changes have changed." with **Reload**. Reload
  keeps the place: the same line of the same file, else its header. The review never reloads by itself.

## Limits
- Text is selected by whole lines; there is no free text selection.
- A viewed binary or too large file stays viewed after Reload even when it changed.
- Viewed flags, review comments and image diffs are not there yet ([ideas](../ideas/review-later.md)).
- A file removed from the index but still on disk (`git rm --cached`) shows as modified; a move that is not staged
  (`mv a b`) shows as a deletion and an addition.

## API
- `GitReviews` (module `git`) is what the review reads from git; `ide` implements the window's `ReviewSource` over it.
  All its calls block and run git on the calling thread; `null` means git failed, timed out or was cancelled.
- A range runs from a full hash, or `null` for the empty tree, to a `GitReviewSide`: a commit or the working tree
  (tracked changes, staged or not, plus the untracked files that are not ignored, as added).
- `defaultBases(root, head)` gives the base candidates for `head` best first (the remote's of `head`'s upstream, or of
  `head` itself if it is a remote branch; `origin`'s; the other remotes'; each `<remote>/HEAD`'s target or else `main`
  then `master`; then the local `main` and `master`), `branches` the local then remote branches, `commits` the commits
  of `from..to` newest first.
- `fileDiffs` reads a commit range's objects with one `git cat-file --batch-check` and its blobs with one
  `git cat-file --batch` (split when the names would not fit git's standard input or the blobs pass 32 MiB; paths with
  a line break or a carriage return are left out, as git would read another file), then one `git diff` per text
  file, with `-w` when whitespace is hidden; working-tree files are compared as the git log compares them. A file that
  cannot be read is left out of the result.
- `fingerprint` hashes what the given revisions resolve to and, for the working tree, `git status --porcelain` and the
  size and modification time of every changed or untracked file.
