# Git Log

**Module:** `modules/git-log` (package `dev.sluide.gitlog`) — `GitLogView` (the area), `GitLogPages` (paging and
reloads, without controls), `GraphLayout` (the commit graph's lanes), `GraphCell` (drawing it, with the ref labels of
`RefLabels` and `refGroups`), `CommitDetails` (which details and changed files to load, without controls),
`CommitDetailsPane`, `changeTree` and `FileDiffs` (`ChangedFiles.kt`, without controls), `ChangedFilesPane` with the
tree `ChangeTreeView` (shared with the working-tree window), `LogComparison` (in `GitLogPages.kt`, without controls),
`WorkingTreeComparison` (without controls) and `WorkingTreeWindow`,
`FilterBar` with `FilterPopup` (`FilterBar.kt`), `branchNodes` (`BranchTree.kt`, without controls) and
`BranchesPane`, the operations that change the repository with `branchActions`, `nameProblem` and their `RefWriter`
(`RefOperations.kt`, without controls), the cherry-pick, revert and reset operations (`CommitOperations.kt`, without
controls), Fetch and its summary (`FetchOperations.kt`, without controls) and their dialogs (`RefDialogs.kt`, `CommitDialogs.kt`); the commits (filtered by a `GitLogFilter`), refs,
details, changed files, diffs, user name, tree paths and the cherry-pick or revert in progress come from `GitLog` in
`modules/git`, the changes are made by `GitBranches`, `GitCommits`, `GitRemotes` (fetch) and `GitPush` there, names are checked
with `GitRefNames`.
Decisions in [ADR 0035](../adr/0035-git-log-area.md), [ADR 0036](../adr/0036-commit-graph-layout.md),
[ADR 0037](../adr/0037-ref-labels-and-commit-details.md), [ADR 0038](../adr/0038-changed-files-and-commit-diffs.md),
[ADR 0039](../adr/0039-git-log-filters.md), [ADR 0040](../adr/0040-git-log-branches-pane.md),
[ADR 0041](../adr/0041-git-write-operations.md), [ADR 0042](../adr/0042-git-log-commit-operations.md),
[ADR 0043](../adr/0043-git-log-fetch.md), [ADR 0044](../adr/0044-git-log-compare.md),
[ADR 0046](../adr/0046-git-log-file-history.md), [ADR 0048](../adr/0048-commit-tab.md) (the Commit tab,
`CommitView`) and [ADR 0049](../adr/0049-push.md) (the Push dialog, `PushDialog`)

- **Where and when:** for a folder whose git status is followed ([git-status.md](git-status.md): git is available and
  the folder has `.git`), the window shows a Git Log area under the editor area. Other folders, and a window without a
  folder, have no such area: no header, no divider.
- **Collapsed by default:** only its header bar shows: a chevron, "Git Log" and Push (an up arrow), Fetch (a down
  arrow) and Refresh buttons on the right. Clicking the header (not its buttons) expands or collapses it, and so does **View → Git Log** (Alt+9, Cmd+9 on macOS),
  which is disabled when there is no area. Expanding moves the focus to the commit table; collapsing while the table
  has the focus gives it back to the editor (the selected tab, or the editor area without tabs). While the Commit tab
  ([commit-and-push.md](commit-and-push.md)) or file histories ([file-history.md](file-history.md)) are open, "Git Log"
  becomes a row of tabs, the Log first, then Commit, then the histories, and the selected tab decides what the area
  shows.
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
- **Details:** right of the table, at first 30 % of the width right of the branches pane (of the log's whole width while
  the pane is hidden; the divider can be dragged; resizing the window keeps both shares while it is open), below the
  changed files, the details of the selected commit as in IntelliJ: the full message with the subject in bold, the short
  hash and the author, the author's email, `on <date> at <time>`, a line `committed on <date> at <time> by <committer>`
  if someone else committed it or in another minute, and its branches and tags with their icons. The texts can be
  selected with the mouse and copied (Shortcut+C after clicking into them, or the message's context menu); long
  messages wrap and the pane scrolls.
  With no or several commits selected it says "No commit selected" or "N commits selected"; if git fails, "The commit
  details could not be read." Moving through the rows keeps the previous details until the next ones are read
  ("Loading…" after 300 ms); the table keeps the keyboard focus
  ([ADR 0045](../adr/0045-git-log-navigation.md) for the next two).
  - **In N branches:** below the refs, the local and remote branches that contain the commit, as in IntelliJ: `In 3
    branches: main, origin/main, feature/x`, `In 1 branch: main` or `In no branch`; the current branch first, then
    local, then remote branches, each by name (ignoring case first); tags and `origin/HEAD` are left out. Beyond 6
    names it says `, …` and a link `Show all (N)` lists them all, until another commit is shown. They are read in the
    background once the details are shown (which never wait for them), up to 10 s; "Looking for branches…" if that
    takes longer than 300 ms, "Branches could not be read." if git fails. Kept per commit until the refs change (a
    refresh that brings other refs reads the shown commit's again) or another folder opens.
  - **Hash links:** words of 7–40 hexadecimal characters in the subject and body (not part of a longer word, any case)
    that name exactly one commit by its hash are links: in the accent colour (lighter in the dark theme), underlined,
    with a hand cursor and a tooltip "Go to <short hash>"; a selection in these texts is pale blue (dark blue in the
    dark theme), so that selected links stay readable. Ambiguous and unknown ones, and branches or tags named like a
    hash, stay text. They are looked up with the details, all of a message with one git call (at most 100). A click
    (not a drag that selects text) selects the commit's row as the branches pane does (pages loaded up to 10,000
    commits, the same messages in the header, the short hash as the name) and gives the table the focus.
- **Changed files:** right of the table, above the details (half and half at first; the divider can be dragged and
  keeps its share while the window is open), the files the selected commit changed, as in IntelliJ: "N files" and a
  tree by path, folders first, then files, by name, everything expanded; folders that hold only one folder are one
  node (`src/main/kotlin`). Folders and files have the explorer's file-type icons (`file-icons` module; folders open or
  closed with the node, files by name and extension, a file of unknown type gets the document icon, nothing is read
  from disk). A merge is compared with its first parent ("N files (compared with the first parent …)"), a root
  commit with nothing (every file added). File names (not the icons) are coloured by their change: green added, blue
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
  the first page (the rows stay until the new ones are there; selected commits stay selected if still shown). In a
  narrow log the text field shrinks (down to 100 px) before the buttons, whose values are cut off with "…" beyond
  160 px; what still does not fit is cut off at the right.
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
- **Branches pane** ([ADR 0040](../adr/0040-git-log-branches-pane.md)): left of the commits and their filter bar, as in
  IntelliJ.
  - **Where:** shown at first in the left 20 % of the log's width (commits and changed files/details keep their 70/30
    split of the rest); its divider can be dragged and keeps its share when the window is resized. The toggle at the
    left end of the filter bar (a tree icon, "Branches", in the accent colour while the pane is shown) hides and shows
    it; hiding it while it has the focus moves the focus to the table. Per window and not stored; opening another
    folder keeps it shown or hidden but clears its search and selection.
  - **Tree:** `HEAD (Current Branch)` (`HEAD (detached at <short hash>)` when detached), then Local, Remote (one node
    per remote, then its branches) and Tags, as read with the log's last first page; groups without refs are left
    out. Branches carry the ref labels' icons (`HEAD` yellow, local green, remote purple, tags grey); the current
    branch is bold with the `HEAD` icon. Names with `/` are grouped into folders, a folder holding only one folder is
    one node (`team/fix`); folders first, then branches, by name (ignoring case first). Tags are collapsed at first,
    the rest expanded. A remote is the first segment after `refs/remotes/`, so a remote name with a `/` is split
    wrongly. Hovering a branch shows its full refname. "Loading…" before the first page, "No branches yet." without
    refs, "The branches could not be read." if git fails. A refresh rebuilds the tree, keeping the selected branches,
    collapsed nodes and the scroll position.
  - **Search:** the field above the tree ("Search branches", as high as the filter bar) shows the branches whose short
    name (`feature/a`, `origin/main`, `v1.0`) has the text, ignoring case, with their folders, everything expanded;
    clearing it brings the collapsed nodes back. Applied 200 ms after the last keystroke (Down, Enter and clearing at
    once). "No matching branches" if none. Escape clears it; Down or Enter moves to the tree and selects the first
    branch. Shortcut+F in the pane (tree or field) focuses the field and selects its text (elsewhere in the log the
    text filter).
  - **Navigate:** selecting exactly one branch (mouse or arrow keys) selects its commit in the table and scrolls it to
    the middle unless it is in view, the focus staying in the tree; the changed files and details follow. Commits not
    loaded yet are loaded page by page, up to 10,000 commits; a newer selection, a filter change or opening a folder
    stops that, while Refresh (or a change in `.git`) lets it go on in the reloaded log. A refresh that moves the
    selected branch selects its new commit if the table still has exactly its old commit selected (or that is still
    being looked for) and the pane is shown; otherwise, and for an unchanged branch, the table's selection stays. If the
    commit is not there, the header says "<name> is not in this comparison." (while comparing), "<name> is hidden by the
    filters." (with filters set), "<name> is not in the
    log." or "<name> is more than 10,000 commits down.", until the next selection. A tag of a tree or blob (not a
    commit) is never in the log, so it gets one of the last two. Groups, folders and several branches do not navigate.
  - **Filter:** a double-click on a branch, Enter, or "Filter Log by Branch" ("Filter Log by N Branches") in the
    context menu sets the Branch filter to exactly the selected branches (Shift/Ctrl select several), as if checked in
    its popup. Enter on a folder opens or closes it. "Copy Name" copies the short names, one per line; both are
    disabled without a branch selected.
- **Go to Hash/Branch/Tag** ([ADR 0045](../adr/0045-git-log-navigation.md)): Shortcut+G in the log body (table,
  changed files, details, filter bar, branches pane) or "Go to Hash/Branch/Tag…" at the end of the table's context menu
  opens a popup at the top of the commit table, as in IntelliJ: a field ("Hash, branch or tag"), focused, and below it
  the branches and tags read with the last first page (local, remote, tags, with the ref labels' icons) whose short
  name has the typed text (ignoring case), at most 10 rows in view; none while the field is empty or nothing matches.
  Down moves into the list, Up from its first item back to the field; Enter or a click on an item goes to it; Enter in
  the field goes to the typed text, or to the one item named exactly. Anything git understands can be typed: a full or
  short hash (at least 4 characters), a branch or tag (short or full refname), `HEAD~3`, `main^2`, `v1.0^{}`,
  `@{upstream}`; annotated tags lead to the commit they tag. On success the popup closes, the commit's row is selected
  and scrolled into view, and the table has the focus. Otherwise the popup stays open, the text selected, and says
  "Unknown revision 'xyz'." (also when git cannot look it up, e.g. `@{upstream}` without one), "'abc1' is
  ambiguous.", or as the branches pane would: "… is not in this comparison.", "… is hidden by the filters." (then with
  a Clear Filters button that clears the text, branches, users, paths and graph options, Regex and Match Case staying
  as they are, and goes on to the commit), "… is not in the log." or "… is more than 10,000 commits down."; "The
  git log could not be read." if a page fails meanwhile (the header says so too, also after a hash link or a branch).
  Escape (or a click elsewhere) closes it without moving. A newer Go to, hash link or branch selection replaces a
  search still loading pages.
- **Copy Revision Number:** in the context menu of the table, and with Shortcut+C while the table has the focus; it
  copies the full hash of the selected commit, or of all selected commits one per line in table order (Shift/Ctrl
  select several).
- **Changing the repository** ([ADR 0041](../adr/0041-git-write-operations.md)), with the git command line, so hooks
  and the user's configuration apply:
  - **Commit table:** its context menu has, after Copy Revision Number, Compare with Local (below) and a separator,
    Checkout Revision (`HEAD`
    detached at the commit), New Branch… and New Tag… (at the commit), and after another separator Cherry-Pick, Revert
    Commit and Reset Current Branch to Here… ([ADR 0042](../adr/0042-git-log-commit-operations.md), below); enabled
    with exactly one commit selected, Reset only while the refs read show a `HEAD` commit. Go to Hash/Branch/Tag…
    (above) follows after a last separator.
  - **Branches pane:** its context menu has, after Copy Name and a separator, Compare with Current and Show Diff with
    Working Tree (below), and after another separator Checkout, "New Branch from '<name>'…"
    (from any one branch, tag or `HEAD`), Rename… (local branches, also the current one; also F2) and Delete (local
    branches and tags, several at once; also Delete, or Shortcut+Backspace as in the explorer). Checkout is disabled
    for `HEAD`, the current branch, a remote branch whose local branch of the same name is the current one, folders,
    groups and several; Delete as soon as the selection holds the current branch, a remote branch, `HEAD`, a folder or
    a group. A local branch is checked out, a tag detached; a remote branch `origin/fix` checks out the local branch
    `fix` if there is one ("Checked out local branch fix."), else creates `fix` tracking it and checks it out
    ("Checked out new branch fix tracking origin/fix."). There are no further keys.
  - **Leaving a detached HEAD:** a checkout (also of a revision from the log, or a new branch with Checkout) that would
    leave commits of a detached `HEAD` on no branch, remote branch or tag asks first: "2 commits of the detached HEAD
    will no longer be on any branch or tag; they stay reachable through the reflog for a while." and below it "To
    keep them, cancel and create a branch at HEAD first. Check out main anyway?", Check Out or Cancel (the default).
    It asks too if that cannot be read.
  - **New Branch** (name, "Checkout branch" checked, "Overwrite existing branch" unchecked), **New Tag** (name, an
    optional message: with one the tag is annotated; the message is kept as typed, also non-ASCII and lines starting
    with `#`, apart from leading and trailing blank lines and trailing spaces, and consecutive blank lines become one)
    and **Rename Branch** (the old name selected): Create or Rename stays disabled, with the reason below the name, for
    an empty name, one git does not take as a branch or tag name (`git check-ref-format`: no spaces, `~ ^ : ? * [ \`,
    `..`, `@{`, a component starting with `.` or ending in `.lock`, …), one starting with `-`, an existing one
    ("Branch 'x' already exists", unless overwriting; "Tag 'x' already exists"; for Rename the old name itself without
    a reason), the checked-out branch when overwriting ("Branch 'main' is checked out.", also without Checkout), and
    one the JVM cannot pass to git in its locale ("This name cannot be passed to git in this locale.", e.g. non-ASCII
    in a POSIX locale). Overwriting another branch says below the name what it does ("Moves 'x' from 1a2b3c4d."); if
    that branch has commits that are not on the start point, a confirmation asks first ("'x' has commits that are not
    on origin/main; they will only be reachable through the reflog. Overwrite?", Cancel the default).
  - **New branches** are created first and then checked out, so a failure never leaves the other commit's files
    staged on the old branch; if git fails, the new branch is deleted again (an overwritten one moved back to its
    commit; an existing branch is never deleted), and if that fails too, the error says so. If where the branch points
    cannot be read, nothing is done ("The commit of branch 'x' could not be read."). A checkout that happened although
    git reported an error (a failing post-checkout hook) is not undone: "Checked out x, but git reported an error."
  - **Delete** always asks first ("Delete branch 'x'?", "Delete 2 branches and 1 tag?", listing "branch x" and "tag
    v1" in an area that scrolls; Cancel is the default). Branches git does not delete with `-d` (not fully merged)
    are offered again with git's message and "Force Delete".
  - **Running:** one operation at a time, in the background; meanwhile all these actions are disabled and the header
    says "Checking out main…", "Creating branch x…" and so on, then "Checked out main.", "Created branch x.", "Created
    and checked out branch x.", "Created tag v1.", "Renamed branch a to b.", "Deleted branch x." or "Deleted 3 refs."
    until the next operation (another message of the log shows instead while there is one). Afterwards, also after a
    failure, the log is refreshed; after a checkout the window title, the explorer's markers and the open files follow
    by themselves. Closing the window lets a running operation finish.
  - **Failures** show an error dialog titled after the operation ("Checkout Failed", …) with git's own message,
    without its `hint:` lines, in a selectable monospace area that wraps long lines, e.g. that local changes would be
    overwritten by the checkout; nothing is stashed. A checkout, cherry-pick, revert, reset, abort or continue may
    take 10 minutes, anything else 60 s ("git did not finish within 60 s.", git is stopped). Git never waits for an
    editor: where it would open one (e.g. a lightweight tag with `tag.gpgSign`), it fails with its message.
  - **Cherry-Pick and Revert Commit** apply or undo the selected commit's changes on the current branch (`git
    cherry-pick`, `git revert --no-edit`): "Cherry-picked 1a2b3c4d as 5e6f7a8b.", "Reverted 1a2b3c4d in 5e6f7a8b.". A
    merge commit asks first ("1a2b3c4d is a merge commit. Cherry-pick the changes it brought in relative to its first
    parent?", Cancel the default) and then uses its first parent (`-m 1`). If there is nothing to commit (the changes
    are already there, or already undone), git's leftover state is cleared and the header says so ("Nothing to
    cherry-pick: the changes of 1a2b3c4d are already on main.", "Nothing to revert: the changes of 1a2b3c4d are
    already undone."); local changes and untracked files stay. Both are disabled while the bar below shows something,
    and refused without running git while anything is in progress ("A merge is in progress; finish or abort it
    first.").
  - **Conflicts:** a dialog "Cherry-Pick Stopped" ("Revert Stopped") lists the conflicted paths ("Cherry-pick of
    1a2b3c4d stopped with conflicts in:"), git's message under Show Details, with Resolve (the default; the files keep
    their conflict markers for the editor) and Abort Cherry-Pick (Abort Revert). The header says "Cherry-pick of
    1a2b3c4d stopped with conflicts."
  - **In progress:** while a cherry-pick or revert is in progress, also one started in a terminal, a bar below the
    filter bar says "Cherry-pick of 1a2b3c4d is in progress: 2 files have conflicts." ("…: conflicts resolved." once
    none is unmerged in the index) with Continue and Abort; read with every refresh, it disappears after one without
    it (and after a refresh that fails). A merge, rebase, `git am` or a cherry-pick or revert of several commits shows
    there too, without buttons ("A cherry-pick sequence is in progress; continue or abort it in the terminal.").
    **Abort** goes back to before it ("Aborted the cherry-pick of 1a2b3c4d."). **Continue** refuses while a text
    conflict still has a line starting with a conflict marker (`<<<<<<< ` or `>>>>>>> `, as long as the file's
    `conflict-marker-size` attribute says; an error "Conflict markers are left in: a.txt, b/c.txt"). Conflicted files
    it cannot check (binary, deleted on one side or on disk, submodules, symbolic links, above 5 MB) are listed in a
    confirmation first ("bin.dat — binary, the version on disk will be committed", Cancel the default, "Commit As on
    Disk"), and again if they changed meanwhile; a submodule that is not checked out is refused ("Submodule sub is not
    checked out: … Resolve it in the terminal."). Then it stages exactly the conflicted files as they are on disk (a
    deleted one as deleted; other local changes are not committed) and commits with git's message ("Cherry-picked
    1a2b3c4d as 5e6f7a8b.").
  - **Reset Current Branch to Here…:** a dialog "Reset Current Branch" ("Reset HEAD" when detached) says "Reset branch
    'main' (at 5e6f7a8b) to 1a2b3c4d" and offers Soft ("Files won't change; the differences will be staged for
    commit."), Mixed (preselected; "…won't be staged.") and Hard ("Files will be changed to the commit's state. Local
    changes to tracked files will be lost.", in a warning colour), then Reset or Cancel. If commits would no longer be
    on any branch, remote branch or tag, or (Hard) tracked files have local changes, or that cannot be read, a
    confirmation says so first ("2 commits will no longer be on any branch or tag; they stay reachable through the
    reflog for a while.", "Local changes in 3 files will be lost.", Cancel the default). Done: "Reset main to 1a2b3c4d
    (hard); it was at 5e6f7a8b."
  - **Unsaved editors:** before an operation that changes files, files of the repository with unsaved changes are
    asked about, naming it: "These files have unsaved changes. Save them before you check out?" with Save, Check Out
    Anyway (the changes stay; an editor whose file the operation changed offers Reload or Keep mine) and Cancel (the
    operation does not run). Asked for checkouts (also of a new branch), Cherry-Pick, Revert, a hard reset, Abort and
    Continue (which stages the files from disk), with "Cherry-Pick Anyway", "Revert Anyway", "Reset Anyway", "Abort
    Anyway" and "Continue Anyway"; not for soft and mixed resets or branch and tag operations.
- **Fetch** ([ADR 0043](../adr/0043-git-log-fetch.md)): the down arrow in the header ("Fetch All Remotes", also while
  the log is collapsed) fetches every remote but those with `remote.<name>.skipFetchAll`, one after the other; Fetch in
  the branches pane's context menu (after Delete and a separator) on a remote, a folder of a remote or a remote branch
  fetches only that remote, also one with `skipFetchAll`; it is disabled where no remote of the repository matches
  (remote branches of a removed remote). `git fetch --prune --recurse-submodules=no`: remote branches deleted on the
  server disappear, tags arrive as git follows them by default (those of the fetched commits); local tags are never
  deleted and tags moved on the server are not moved locally. No file changes, so no question about unsaved editors.
  - **Running:** like the other operations, one at a time and with all of them disabled meanwhile (the Fetch button
    too); the header says "Fetching origin…" with a **Cancel** button next to it, which stops git (and ssh or whatever
    it started) and skips the remaining remotes: "Fetch cancelled." and, if anything changed before, "Fetched so far:
    1 new branch." Opening another folder or closing the window cancels a running fetch too; refs git updated by then
    stay updated. A remote may take 10 minutes ("git did not finish within 10 min.").
  - **Done:** the header says what changed, read from the refs before and after (where the remote's refspecs write,
    and the tags): "Fetched origin: 1 new branch, 2
    branches updated, 1 branch pruned, 3 new tags.", "Fetched 2 remotes: nothing new.", "No remotes to fetch.". A
    remote that fails does not stop the others: an error dialog "Fetch Failed" says "gone could not be fetched." (or
    "2 remotes could not be fetched: a, b.") with git's message (each under its remote's name), and the header tells
    what the others fetched ("Fetched 1 of 2 remotes: …"; "Fetched origin with errors: 1 branch updated." when git
    refused a tag but moved branches). Remotes that failed before a cancel get the dialog too. The log is refreshed
    afterwards.
  - **Never asks:** git gets no terminal prompt; credential helpers work. Ssh runs in batch mode (unless
    `GIT_SSH_COMMAND`, `GIT_SSH`, `GIT_SSH_VARIANT`, `core.sshCommand` or `ssh.variant` is set): a key with a
    passphrase needs an ssh agent, an unknown host a first connection from a terminal; otherwise the fetch fails with
    ssh's message.
  - **Disabled** while another operation runs, and once the log found no remotes (they are read with every refresh
    of the expanded log); before the log was first expanded it is enabled and says "No remotes to fetch." if there
    are none.
- **Push** ([ADR 0049](../adr/0049-push.md)): the up arrow in the header ("Push… (Ctrl+Shift+K)", also while the log
  is collapsed, left of Fetch) opens the Push dialog, as Git → Push… does ([commit-and-push.md](commit-and-push.md));
  disabled once the log found no remotes, but not while an operation runs (the dialog's Push waits for it). The push
  runs like a fetch: "Pushing main to origin/main…" with Cancel, then what was pushed, "Push Failed" with git's
  message for a rejection.
- **Compare** ([ADR 0044](../adr/0044-git-log-compare.md)), only reading:
  - **Compare with Current** in the branches pane's context menu, on one local branch, remote branch (also the current
    branch's upstream) or tag that is not `HEAD` or the current branch (disabled for those, folders, groups, several and
    while `HEAD` has no commit): the log lists the commits of the ref that are not in `HEAD`, with a bar below the
    filter bar (pale blue): "Comparing feature with main" ("with HEAD" when detached; the whole text in a tooltip when
    the bar is narrow), the toggles "In feature, not in main (3)" and "In main, not in feature (5)" (counting the
    commits that pass the other filters too, as listed; exactly one selected, the first at first; a click on the
    selected one keeps it) and × ("Stop Comparing"; Escape in the table too). The text, user and path filters and the
    graph options still apply; the Branch button is disabled meanwhile and its selection comes back when comparing
    stops, as do all filters. "Filter Log by Branch" in the pane stops comparing first. An empty side says "No commits
    in feature that are not in main." (without other filters set, else "No commits match the filters."). The graph is
    drawn as for a branch filter; lanes to parents outside the range run to the end of the list. No hash match while
    comparing. The counts are read with every first page, so a refresh (Refresh, a commit, checkout or fetch) counts
    again against what `HEAD` is then. A refresh without the compared ref stops comparing ("feature no longer exists."
    in the header, also with a commit selected), so does one where it is checked out ("feature is the current branch
    now."). Opening another folder stops it too.
  - **Show Diff with Working Tree** (branches pane, any one branch, remote branch, tag, also `HEAD` and the current
    branch) and **Compare with Local** (commit table, one commit) open a window of their own, titled "feature ↔ Working
    Tree" ("1a2b3c4d ↔ Working Tree"): not modal and not owned by the IDE window, which can come in front of it; with
    the IDE window's icons and theme (also dark), first placed at the IDE window's right edge. The same ref or commit
    again brings its window to the front; another folder and closing the IDE window close them all. It shows "4 files
    differ" ("1 file differs") with a Refresh button, the files that differ between that commit and the working tree as
    the changed files' tree (added in the working tree green, modified and renamed blue with ` ← old`, deleted grey) and
    "Untracked files are not included." below; only tracked files are compared (`git diff --name-status -M <commit>`),
    so new files count once they are added to the index. "Loading…", "No differences.", "feature no longer exists." (a
    window of a branch follows the branch: it is resolved again with every refresh) and "The differences could not be
    read." stand in for the tree. It reads the files again with Refresh and whenever it gains the focus (edits of the
    working tree are not watched), keeping the selected file and collapsed folders.
  - **Diffs:** Show Diff (double-click, Enter, Shortcut+D or the context menu) on a file of the window opens an editor
    tab "App.kt (feature ↔ Working Tree)": left the file at the commit ("feature (1a2b3c4d)", with ", old.txt" for a
    renamed file, "(none)" for an added one), right "Working Tree", the file as it is on disk (empty for a deleted file,
    titled "Working Tree (deleted)"; "Working Tree (saved)" while an editor has unsaved changes of it), side by side and
    read-only; the IDE window comes to the front with the tab focused. The same file of the same commit again shows it
    read again in its tab, also a deleted file that is back; the tab does not follow later edits by itself, and a save
    between reading the hunks and the file misaligns the colouring until Show Diff again. Binary files, files above 5
    MB, submodules, files whose content did not change and files no text viewer shows are not compared; the window's bar
    says so ("data.bin is a binary file.", "… is too large to compare.", "lib is a submodule.", "… has no content
    changes.", "… cannot be shown as text."), until the next selection. Open Local File (F4) and Copy Path work as in
    the changed files. The commit's side is shown as a checkout would write it, with the line endings and filters of the
    file's attributes, the working tree's as on disk; the two are compared as they are, whatever the attributes say
    about diffing. A smudge filter (git-lfs) that makes the file larger than 5 MB gives "… is too large to compare.";
    one that fetches over the network may make Show Diff wait up to 10 s before "The diff could not be read."
- Not yet implemented (next packages): a flat list of the changed files, their combined changes for several commits, comparing two branches or commits with each other (and the
  files since their merge base), the changes against each parent of a merge, a diff preview in the log, a date filter, dotted
  edges through commits hidden by filters, tracking information in the branches pane, remembering the area's height
  and state, and more operations: updating local branches after a fetch, pull, merge, rebase, deleting remote branches, pushing tags; for
  cherry-pick and revert several commits at once, a merge tool or three-way view, `-x` and edited messages; `reset
  --keep`; continuing or aborting merges, rebases and sequences from the bar.
