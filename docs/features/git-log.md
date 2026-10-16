# Git Log

**Module:** `modules/git-log` (package `dev.sluide.gitlog`) — `GitLogView` (the area), `GitLogPages` (paging and
reloads, without controls), `GraphLayout` (the commit graph's lanes), `GraphCell` (drawing it, with the ref labels of
`RefLabels` and `refGroups`), `CommitDetails` (which details and changed files to load, without controls),
`CommitDetailsPane`, `changeTree` and `FileDiffs` (`ChangedFiles.kt`, without controls), `ChangedFilesPane` and
`FilterBar` with `FilterPopup` (`FilterBar.kt`); the commits (filtered by a `GitLogFilter`), refs, details, changed
files, diffs, user name and tree paths come from `GitLog` in `modules/git`. Decisions in
[ADR 0035](../adr/0035-git-log-area.md), [ADR 0036](../adr/0036-commit-graph-layout.md),
[ADR 0037](../adr/0037-ref-labels-and-commit-details.md), [ADR 0038](../adr/0038-changed-files-and-commit-diffs.md)
and [ADR 0039](../adr/0039-git-log-filters.md)

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
  keeps both shares while it is open), below the changed files, the details of the selected commit as in IntelliJ: the
  full message with the subject in bold, the short hash and the author, the author's email, `on <date> at <time>`, a
  line `committed on <date> at <time> by <committer>` if someone else committed it or in another minute, and its
  branches and tags with their icons. The texts can be selected with the mouse and copied (Shortcut+C after clicking
  into them); long messages wrap and the pane scrolls. With no or several commits selected it says "No commit selected"
  or "N commits selected"; if git fails, "The commit details could not be read." Moving through the rows keeps the
  previous details until the next ones are read ("Loading…" after 300 ms); the table keeps the keyboard focus.
- **Changed files:** right of the table, above the details (half and half at first; the divider can be dragged and
  keeps its share while the window is open), the files the selected commit changed, as in IntelliJ: "N files" and a
  tree by path, folders first, then files, by name, everything expanded; folders that hold only one folder are one
  node (`src/main/kotlin`). A merge is compared with its first parent ("N files (compared with the first parent
  …)"), a root commit with nothing (every file added). File names are coloured by their change: green added, blue
  modified (also renamed, copied, type changed), grey deleted; lighter in the dark theme, the selection text colour
  when selected. A renamed file shows ` ← old/path` and a tooltip; renames are found as git's `-M` finds them.
  Placeholders: "No commit selected", "N commits selected", "Loading…" (after 300 ms, as for the details), "No
  changed files", "The changed files could not be read.". They are read right after the details (which do not wait
  for them) and again on a refresh, which keeps the selected file and the collapsed folders.
- **Show Diff / Open Local File:** on a file of the tree (not a folder), Show Diff (double-click, Enter, Shortcut+D or
  the context menu) opens what the commit changed in it in an editor tab, "<name> (<short hash>)": the file at the first
  parent (titled with its short hash and, for a renamed file, the old path; "(none)" for an added file) left, at the
  commit right, side by side and read-only, as in [git-diff.md](git-diff.md). Short hashes have 8 characters, as in the
  details. The same file of the same commit again selects its tab. Binary files, files above 5 MB, submodules, files
  whose content did not change (a pure rename, a changed mode) and text that no text viewer shows (images) are not
  compared; the header says so ("README.png is a binary file.", "… is too large to compare.", "lib is a submodule:
  1a2b3c4d → 5e6f7a8b.", "run.sh has no content changes.", "… cannot be shown as text."), until the next selection; if
  the selection changed while the diff was read, nothing is said. Open Local File (F4 or the context menu) opens the
  file as it is now in the editor; it is disabled for files not in the working tree (e.g. deleted). Copy Path copies the
  path relative to the repository. Enter on a folder opens or closes it.
- **Loading:** nothing is read before the area is first expanded. Then 500 commits load in the background; scrolling
  to within 50 rows of the last one loads the next 500, until there are no more. In very large repositories without
  a commit-graph file (`git gc` writes one), git sorts the whole history for every page, so the first page can hit the
  10 s limit ([ADR 0035](../adr/0035-git-log-area.md)).
- **Messages:** "Loading…" while the first page loads; "No commits yet." for a repository without commits; "No commits
  match the filters." when filters are set and nothing passes; "The git log could not be read." if git fails or takes
  longer than 10 s, also for an invalid regex (with rows already shown, they stay and the message appears in the
  header).
- **Refresh:** the Refresh button, and changes directly in `.git` (commits, checkouts, staging, fetches, merges,
  rebases) reload the first page and the refs; while collapsed the log only notes it and reloads on the next expand.
  Selected commits stay selected if they are still in the first page, and the details are read again. A new branch or
  tag alone (`git branch x`, `git tag x`) is not noticed; use Refresh.
- **Filters** ([ADR 0039](../adr/0039-git-log-filters.md)): a bar above the commit table (not above the changed files
  and details), as in IntelliJ. Git itself filters, so a filtered log still loads in pages. Changing a filter reloads
  the first page (the rows stay until the new ones are there; selected commits stay selected if still shown).
  - **Text or hash:** matches the whole commit message, any line; applied 300 ms after the last keystroke, at once on
    Enter; Escape in the field clears it. Trimmed. `.*` (Regex) makes it a POSIX extended regular expression, else it
    is a literal; `Cc` (Match Case) matches case, else case is ignored; toggling either with a text reloads at once.
    A text of 4–64 hexadecimal characters that names exactly one commit shows that commit as the first row (once),
    ahead of the message matches, if it passes the user, path and no-merges filters; the branch filter is not
    applied to it. Ambiguous and unknown prefixes match nothing by hash.
  - **Branch:** lists `HEAD`, then the local branches, remote branches and tags (each by name, with the coloured tag
    icons of the ref labels) as read with the last first page; the log then shows the commits reachable from the
    checked ones only. A checked branch that is deleted later is skipped.
  - **User:** lists "me (<user.name>)" if git has a user name for the repository, then the authors of all rows loaded
    since the folder was opened (case-insensitively by name). A checked value matches the author's name or email
    exactly, ignoring case (also with Match Case), after `.mailmap` (also with `log.mailmap=false`); several are ORed,
    and ANDed with the text.
  - **Path:** lists the folders and files of `HEAD`'s tree (read when the popup is first opened, and again after
    Refresh or a change in `.git`); a folder includes everything below it. Git's default history simplification
    applies, as for `git log -- <path>`; renames are not followed, also with `log.follow=true`. Typed paths lose a
    leading `./` or `/` and a trailing `/`; a path with a `..` segment is not taken.
  - **Popups:** a search field (focused) above the items with check boxes, the checked ones first; typing narrows the
    list (ignoring case). Clicking an item or Space toggles it and applies the filter at once; the popup stays open.
    Enter toggles the one item shown, or, with several shown, the one named exactly (checks or unchecks it); for User
    and Path, other text is added as a checked value of its own. Down moves into the list, Escape closes. A double
    click toggles once. The buttons say "Branch", "Branch: main" or "Branch: main +2" (the first checked in list
    order; "User: me"); a × next to a button, there only while it filters, clears it.
  - **Graph Options** (cog): Sort by Date (default) or Sort Topologically, First Parent Only, No Merges. While any
    differs from the default, the cog is marked in the accent colour (as a selected toggle) and its tooltip lists
    what is on ("Graph Options: First Parent Only").
  - **Graph:** with a branch or path filter the graph is drawn as usual, in either order (git gives the nearest
    shown commits as parents); First Parent Only follows only first parents. Under a text or user filter, a hash
    match or No Merges the rows have no graph (the subject starts at the left), as their parents may be hidden.
  - **Keyboard:** Shortcut+F in the log body (table, changed files, details) focuses the text field and selects its
    text; Down there moves to the table. Tab goes on to Regex, Match Case, Branch (×), User (×), Path (×) and the cog,
    with a focus ring; Space or Enter on a button opens its popup, Space, Enter or Down on the cog its menu.
  - Filters belong to the window and are not stored; Refresh and changes in `.git` keep them, opening another folder
    resets them. Non-ASCII characters in the text or a user work where the JVM's locale cannot pass them as arguments,
    matching slightly more (not inside `[…]` of a regex).
- **Copy Revision Number:** in the context menu of the table, and with Shortcut+C while the table has the focus; it
  copies the full hash of the selected commit, or of all selected commits one per line in table order (Shift/Ctrl
  select several).
- Not yet implemented (next packages): "In N branches" and clickable hashes in the details, a flat list of the
  changed files, their combined changes for several commits, comparing with the local version, the changes against
  each parent of a merge, a diff preview in the log, file history, file-type icons in the tree, a date filter, dotted
  edges through commits hidden by filters, the branches pane, remembering the area's height and state, and any action
  that changes the repository.
