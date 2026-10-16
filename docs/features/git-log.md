# Git Log

**Module:** `modules/git-log` (package `dev.sluide.gitlog`) — `GitLogView` (the area), `GitLogPages` (paging and
reloads, without controls), `GraphLayout` (the commit graph's lanes), `GraphCell` (drawing it, with the ref labels of
`RefLabels` and `refGroups`), `CommitDetails` (which details to load, without controls) and `CommitDetailsPane`; the
commits, refs and details come from `GitLog` in `modules/git`. Decisions in [ADR 0035](../adr/0035-git-log-area.md),
[ADR 0036](../adr/0036-commit-graph-layout.md) and [ADR 0037](../adr/0037-ref-labels-and-commit-details.md)

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
- **Graph:** left of every subject, the commit graph as in IntelliJ: a coloured dot for the commit on its branch's
  lane, lines down to its parents, a new lane opening to the right at a merge and lanes converging where branches
  forked off; a branch tip starts a lane of its own at the right. Every row's subject starts right after that row's
  graph. The 8 lane colours repeat and have lighter variants in the dark theme; selected rows keep them, with a halo in
  the table's background colour around lines and dots. At most 30 lanes are drawn; lines into lanes further right are
  cut off at the graph's edge. Colours are given from the top, so they can change after a refresh brings a new branch
  tip; long lines are not shortened into arrows ([ADR 0036](../adr/0036-commit-graph-layout.md)).
- **Ref labels:** at the right end of the subject, the branches and tags pointing at the commit, as in IntelliJ: a
  coloured tag icon for each kind (`HEAD` yellow, local branches green, remote branches purple, tags grey; lighter in
  the dark theme), then the first group's name and `+N` for the others. A local branch and the remote branches of the
  same name are one group (`origin & main`); `HEAD` joins the checked-out branch, a detached one is shown as `HEAD`;
  a remote branch without a local one is `origin/main`. The labels take at most a third of the cell, so in a narrower
  column they shrink together with the subject (their text ellipsized), leaving the subject at least 60 px while there
  is room. In selected rows the icons have a thin outline in the table's background colour. Hovering the labels lists
  every ref with its icon.
  Symbolic refs such as `origin/HEAD` are left out, annotated tags label the commit they tag.
- **Details:** right of the table, at first 30 % of the log's width (the divider can be dragged; resizing the window
  keeps both shares while it is open), the details of the selected commit as in IntelliJ: the full message with the
  subject in bold, the short hash and the author, the author's email, `on <date> at <time>`, a line
  `committed on <date> at <time> by <committer>` if someone else committed it or in another minute, and its branches
  and tags with their icons. The texts can be selected with the mouse and copied (Shortcut+C after clicking into them);
  long messages wrap and the pane scrolls. With no or several commits selected it says "No commit selected" or
  "N commits selected"; if git fails, "The commit details could not be read." Moving through the rows keeps the
  previous details until the next ones are read ("Loading…" after 300 ms); the table keeps the keyboard focus.
- **Loading:** nothing is read before the area is first expanded. Then 500 commits load in the background; scrolling
  to within 50 rows of the last one loads the next 500, until there are no more. In very large repositories without
  a commit-graph file (`git gc` writes one), git sorts the whole history for every page, so the first page can hit the
  10 s limit ([ADR 0035](../adr/0035-git-log-area.md)).
- **Messages:** "Loading…" while the first page loads; "No commits yet." for a repository without commits; "The git
  log could not be read." if git fails or takes longer than 10 s (with rows already shown, they stay and the message
  appears in the header).
- **Refresh:** the Refresh button, and changes directly in `.git` (commits, checkouts, staging, fetches, merges,
  rebases) reload the first page and the refs; while collapsed the log only notes it and reloads on the next expand.
  Selected commits stay selected if they are still in the first page, and the details are read again. A new branch or
  tag alone (`git branch x`, `git tag x`) is not noticed; use Refresh.
- **Copy Revision Number:** in the context menu of the table, and with Shortcut+C while the table has the focus; it
  copies the full hash of the selected commit, or of all selected commits one per line in table order (Shift/Ctrl
  select several).
- Not yet implemented (next packages): "In N branches" and clickable hashes in the details, the changed files of the
  selected commit, text/branch/user/path filters, the branches pane, remembering the area's height and state, and any
  action that changes the repository.
