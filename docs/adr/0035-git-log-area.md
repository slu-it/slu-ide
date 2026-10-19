# 0035. Git log area under the editor, read from the git command line in pages

- Status: accepted; partly superseded by [0062](0062-git-concurrency.md) (`gitFolderChanged` counts only changes of
  what the log reads; one reload per write)
- Date: 2026-10-16

## Context
We are rebuilding IntelliJ's Git Log tab step by step. The first package needs a place for it in the window, a list
of the commits that scales to large repositories, and a way to keep it current, while later packages add a commit
graph, ref labels, a details pane, changed files, filters and a branches pane. Today Thoreau reads git only through
the command line (status, diff to `HEAD`, the ref in the title, [ADR 0013](0013-git-status.md)).

## Decision
- **Where:** a collapsible area under the editor area (not under the explorer): `MainWindow` puts the editor area and
  `GitLogView` into a vertical `SplitPane` on the right of the explorer split, which stays the outer one. The area
  exists only when `GitStatusService` follows the opened folder (git available and the folder has `.git`); for any
  other folder, or none, there is neither its header nor a divider.
- **Collapsed by default:** only the header bar ("Git Log", a chevron, Refresh) shows; clicking it, or View → Git Log
  (Alt+9 like IntelliJ, Cmd+9 on macOS, where Alt+digit types characters), expands and collapses it. Collapsed, the
  view's maximum height is its preferred height (the header's) and the divider sits at the bottom, so it cannot be
  dragged open. Expanded, it takes the lower 30 % at first and later its last height again: kept in pixels when it
  collapses and turned back into a divider position for the split's height at that time, so a window resized while
  the log was collapsed does not change it; within the window's lifetime (not stored). The log does not grow with the
  window (`setResizableWithParent(false)`). Collapsing it while the commit table has the focus gives the focus to
  the editor (the selected tab's content, else the editor area).
- **Lazy loading in pages:** nothing runs `git log` before the area is first expanded. Commits load 500 at a time on
  a background thread (`sluide-git-log`); when the last visible row is within 50 rows of the last loaded one, the
  next page is appended, until a page comes back shorter than 500. The table is a virtualized `TableView`; the last
  visible row is reported by the table's `VirtualFlow` after each layout and scroll (not by the row factory, whose
  cells are also used to measure rows that are not shown). The paging, run and selection logic lives in
  `GitLogPages`, without controls.
- **Command:** `git --no-optional-locks log --branches --remotes --tags --ignore-missing HEAD --date-order -z
  --no-color --no-show-signature --encoding=UTF-8 --skip=N --max-count=500
  --format=%H%x1f%P%x1f%aN%x1f%aE%x1f%at%x1f%s --`, 10 s timeout:
  - `--branches --remotes --tags HEAD` instead of `--all`, so stash and notes commits stay out, and a detached
    `HEAD` is still shown;
  - `--ignore-missing` makes an unborn `HEAD` (a repository without commits, or an orphan branch) count as absent
    instead of failing, so an empty first page means "No commits yet."; the final `--` keeps a file named `HEAD`
    from making the argument ambiguous;
  - `%aN`/`%aE` apply `.mailmap` like the user's git; the date shown is the author date (IntelliJ's default), the
    order git's date order;
  - `--encoding=UTF-8`: the output is read as UTF-8, whatever `i18n.logOutputEncoding` the repository sets;
  - records end with NUL and fields are separated by U+001F (`GitLogParser`); malformed records are skipped.
- **Refresh:** the Refresh button, and every change `GitStatusService` notices directly in `.git` (its new
  `gitFolderChanged` counter: `HEAD`, `index`, `packed-refs`, `FETCH_HEAD`, `ORIG_HEAD`, …). Collapsed, the log is only
  marked outdated and reloads on the next expand. A reload replaces the rows with a fresh first page and keeps the
  selected commits selected by hash if they are in it. Results of runs for a previously opened folder or superseded
  by a newer run are dropped; a failed or timed-out run keeps the rows and says "The git log could not be read."
- **Columns:** a table resize widens or narrows only Subject; when it would get narrower than its minimum, Author
  and Date shrink proportionally down to their minimums, so Date stays in view; below the sum of all minimum widths
  the last columns are cut off (a constrained table has no horizontal scroll bar). Dragging a column's edge resizes
  the next column, and the new widths are the ones Author and Date return to.
- **Module:** a new `modules/git-log` (package `dev.sluide.gitlog`) with the window-scoped `GitLogView`
  (`AutoCloseable`, closed with the window's beans, [ADR 0014](0014-project-windows.md)). It depends on `modules/git`
  (`api`) for `GitLog`, `GitCommit` and `GitLogPage`; `git` stays free of UI beyond what it had. The table sits in a
  `SplitPane` of its own so that a details pane can go to its right later; rows keep the whole `GitCommit`, including
  its parents, for the graph.
- **JGit considered again and rejected:** its `revplot` would give a ready-made graph lane layout, but the log would
  then read the repository with a second git implementation next to the CLI used for status and diff, which can
  disagree (`.mailmap`, replace refs and grafts, partial clones, newer repository formats); write operations need the
  CLI anyway (hooks, credential helpers, signing). The graph is our own layout, decided in
  [ADR 0036](0036-commit-graph-layout.md).

## Consequences
- Only 500 commits are parsed and shown per step, but git's work is not limited to them. With a commit-graph
  (`.git/objects/info/commit-graph`) git can produce the date order incrementally. Without one, `--date-order` makes
  git walk and sort the whole history before the first record, on every page; in very large repositories the first
  page can then take longer than the 10 s timeout and show "The git log could not be read." `git gc` writes the
  commit-graph by default (`gc.writeCommitGraph`), and it runs by itself now and then, e.g. after fetches
  (`gc --auto`, `maintenance --auto`); `fetch.writeCommitGraph` makes every fetch write it. Every further page also
  makes git walk the history again up to it (`--skip`), so deep scrolling gets slower page by page.
- A branch or tag created without touching anything directly in `.git` (e.g. `git branch x`, `git tag x`, which only
  write below `.git/refs/`) is not noticed; Refresh picks it up. Repositories with a `.git` file (worktrees,
  submodules) are not watched at all and refresh only by hand.
- Changes of the index (`git add`) reload an expanded log too; that is one cheap `git log` per change.
- If refs change between two pages without a reload (see above), a page may repeat or skip commits until the next
  reload.
- The area's height and expanded state are forgotten when the window closes; remembering them is a later package.
- Alt+9 typed in a terminal goes to the shell, like other Alt keys there.

Note 2026-10-19: `gitFolderChanged` now counts a batch of `.git` only if it changed a stamp of what the status, the log
and the refs read (`HEAD`, index, loose, packed and reftable refs, `FETCH_HEAD`, …; not objects or reflogs), so such a batch also
counts a new branch below `.git/refs/` that the watch itself does not see; a git write of the window counts once, at
its end, so the log reloads once per write. A reload stops the git of a page still loading ([ADR
0062](0062-git-concurrency.md), partly superseding this).
