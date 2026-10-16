# Git Log

**Module:** `modules/git-log` (package `dev.sluide.gitlog`) — `GitLogView` (the area), `GitLogPages` (paging and
reloads, without controls); the commits come from `GitLog` in `modules/git`. Decision in
[ADR 0035](../adr/0035-git-log-area.md)

- **Where and when:** for a folder whose git status is followed ([git-status.md](git-status.md): git is available and
  the folder has `.git`), the window shows a Git Log area under the editor area. Other folders, and a window without a
  folder, have no such area: no header, no divider.
- **Collapsed by default:** only its header bar shows: a chevron, "Git Log" and a Refresh button on the right.
  Clicking the header (not Refresh) expands or collapses it, and so does **View → Git Log** (Alt+9, Cmd+9 on macOS),
  which is disabled when there is no area. Expanding moves the focus to the commit table; collapsing while the table
  has the focus gives it back to the editor (the selected tab, or the editor area without tabs).
- **Height:** expanded, the area takes the lower 30 % of the editor column at first; its divider can be dragged.
  Collapsing and expanding again restores the last height in pixels while the window is open, also if the window
  was resized meanwhile. Collapsed, the divider cannot be dragged. Resizing the window resizes the editor area, not the log.
- **Commits:** a table with Subject (taking the remaining width when the window is resized; in a very narrow one
  Author and Date shrink too, down to their minimum widths, so Date stays in view), Author and Date (the author
  date, short and localized, in the system time zone). It lists the commits of all local branches, remote branches,
  tags and `HEAD` (also when detached), newest first in git's date order; stashes are left out. Author names and
  emails follow `.mailmap`; subjects and names are read as UTF-8 whatever `i18n.logOutputEncoding` says. Columns
  are not sortable.
- **Loading:** nothing is read before the area is first expanded. Then 500 commits load in the background; scrolling
  to within 50 rows of the last one loads the next 500, until there are no more. In very large repositories without
  a commit-graph file (`git gc` writes one), git sorts the whole history for every page, so the first page can hit the
  10 s limit ([ADR 0035](../adr/0035-git-log-area.md)).
- **Messages:** "Loading…" while the first page loads; "No commits yet." for a repository without commits; "The git
  log could not be read." if git fails or takes longer than 10 s (with rows already shown, they stay and the message
  appears in the header).
- **Refresh:** the Refresh button, and changes directly in `.git` (commits, checkouts, staging, fetches, merges,
  rebases) reload the first page; while collapsed the log only notes it and reloads on the next expand. Selected
  commits stay selected if they are still in the first page. A new branch or tag alone (`git branch x`, `git tag x`)
  is not noticed; use Refresh.
- **Copy Revision Number:** in the context menu of the table, and with Shortcut+C while the table has the focus; it
  copies the full hash of the selected commit, or of all selected commits one per line in table order (Shift/Ctrl
  select several).
- Not yet implemented (next packages): the commit graph, ref labels (branches, tags) on the rows, the details of the
  selected commit, its changed files, text/branch/user/path filters, the branches pane, remembering the area's height
  and state, and any action that changes the repository.
