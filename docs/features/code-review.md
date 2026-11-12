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
  offset by 30 px (80 % of the screen; the size is not remembered). Its title is `Review – <scope> – <project>`;
  Shortcut+W closes it (the running load is cancelled), and closing the project window closes its reviews.
- Git → Review Branch sits after Pull… in the Git menu.

## Scope and pickers
- A branch is reviewed against its base like a pull request: from where it left the base (the merge base) to its
  last commit, so changes merged in from the base do not show. The base is the remote's default branch
  (`origin/main` and the like; first that of the remote the reviewed branch tracks, or is on for a remote branch, then
  `origin`'s), else the local `main` or `master`; **into: <base> ▾** on the toolbar picks another branch. Without any
  default branch the review asks to choose a base.
- The **commit picker** offers "All changes", the single commits (newest first) and, while the reviewed branch is
  checked out and has them, **Uncommitted changes** (untracked files included) below it. The button shows the pick
  ("All changes", `abc1234 Subject`, `abc1234..def5678 (3 commits)`); it opens a list with "All changes (N commits)"
  and the commits as `abc1234  Subject — Author, 3 days ago` (dates after 30 days as `yyyy-MM-dd`). A click picks
  one entry, Shift+click the range from the last clicked commit; Up/Down move, Shift+Up/Down extend a range, Enter
  picks, Escape or a click outside closes. A merge commit alone is shown against its first parent. A single-commit
  review has no picker. The base picker is a menu button (the default bases first, marked "(default)", then the rest);
  the toolbar starts with the review's title in bold.
- Empty scopes say so ("No changes in origin/main..feature-x." with the scope's title); git failures show "Could
  not read the review: …".

## Files
- The tree on the left lists the changed files, folders first, single-child folder chains compacted
  (`src/main/kotlin`), names coloured as added, modified or deleted (deleted ones struck through), renamed files with
  their old path in the tooltip, viewed files with a check mark. "x / y viewed" stands above it.
- The **filter** field (Shortcut+P) narrows the tree and the diff to the files whose path contains the text, ignoring
  case. Escape clears it, Down selects the first file and moves into the tree, Enter scrolls the diff to the first
  match and focuses the diff.
- Clicking a file or pressing Enter on it scrolls the diff to it (arrow keys only move the selection; Enter on a
  folder opens or closes it). Scrolling the diff selects the file at its top in the tree, opens its folders and
  scrolls the tree only if that row is out of view, never the diff. A file clicked in the tree that is too near the
  end to reach the diff's top stays selected while the diff shows its header and its end. Folders the user closed
  stay closed when the tree is built again (a reload, a filter keystroke).

## Diff
- **Split** (the default) or **Unified**, toggled at the right end of the toolbar; **Hide whitespace** next to them.
  Both choices are remembered for the next review (`shared.json`, field `review`: only values other than Split and
  off are written). Switching the layout keeps the line at the top of the diff; Hide whitespace reads the diffs again
  (not the file list) and resets the revealed lines; the file at the top stays at the top (its header).
- Each file starts with a header: collapse chevron, path (`old → new` for renames), the counts of added and removed
  lines, and **Viewed**, which collapses the file until unchecked. The window remembers each file's content that was
  viewed for its lifetime: another content (Reload after a change, or one commit instead of all changes) shows the
  file unchecked, and the same content again (back to "All changes") checked again. The current file's header stays at the top while scrolling.
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
  view: left numbers the old side, right numbers the new side; filler halves are skipped). The unified view selects
  a row by either number column (a removed-only row by its old line); a selection there may mix sides. Selected
  numbers and text get a tint over the colour of the change. Shortcut+C copies the lines from top to bottom,
  without − and +; Escape clears the selection (and does nothing else when there is none).
- The selection follows its lines when the layout switches; it is cleared when its file is filtered out or collapsed,
  by Reload, and by Hide whitespace. Expanding a gap inside it leaves the revealed lines
  unselected.
- A right click on a line selects it first when it is not selected. The context menu of selected lines (also Shift+F10
  or the menu key on the diff): Copy Lines, Open in Editor (the file in the working tree at that line, for a removed
  line the nearest new one; disabled, with "(not in the working tree)", when it is not a file there), Copy Path.
- A file header's menu, also on the sticky one: Open in Editor, Show History, Copy Path, Mark as Viewed / Not Viewed.
- F7 / Shift+F7 and Alt+Down / Alt+Up go on from the row they went to last while it is shown, else from the first
  selected row while one is shown, else from the first row below the sticky header. Keys work while the diff has
  the focus.

## Find
- Shortcut+F opens a find bar above the diff: literal text, ignoring case unless Match Case is on, in all rows shown
  of the files that are not collapsed. It counts the matches ("3 / 17"), Enter / Shift+Enter or F3 / Shift+F3 move
  between them, Escape closes the bar.

## Refresh
- When the reviewed commits or, for reviews reaching the working tree, the uncommitted changes change (a commit, an
  amend, a fetch moving the base, an edit), a bar says "The reviewed changes have changed." with **Reload**. Reload
  keeps the place: the same line of the same file, else its header, and the picked commits when they are still there;
  it does not jump when you scroll while the file loads. The check also runs when the window gains the focus, and never
  while loading. The review never reloads by itself.

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
- `GitReviewSource` (`ide/.../git`) is the `ReviewSource` over `GitReviews`: per call a `GitCancellation` is cancelled
  with the load's `ReviewCancellation`; a `null` from git becomes `CancellationException` if cancelled, else a
  `ReviewException` with a short reason. A branch review's title is `<head> into <base>` (the branch's short name, for
  `HEAD` the current branch or the short hash when detached); `uncommitted` is set when the head is the checked-out one
  (`HEAD` or the current branch's refname) and has changes. `ResolvedReview.headRef` keeps that refname or `HEAD`,
  since `head` is a hash that never moves; the fingerprint covers the base ref, `headRef`, the hashes, and for the
  checked-out head the working tree. A commit range runs from the oldest commit's first parent (none for a root
  commit) to the newest; one commit is titled `<short hash> <subject>`, a range `<oldest>..<newest>`.
- `WindowReviews` (`ide/.../git`, created by `WindowGitLog`) is the window part: the git log's requests become scopes
  (several commits: oldest = the last selected, newest = the first), a file link opens the file in the editor area at
  its 0-based line, Show History expands the git log on the file's history (both bring the IDE window to the front),
  and the status snapshot and the `.git` folder's changes tell the open reviews to check for changes.
- A branch that does not resolve is "The branch has no commits yet." when it is the checked-out one (`HEAD` or its
  refname), else "The branch <name> no longer exists."; a base that does not resolve is "The base <name> no longer
  exists."
