# File History

**Module:** `modules/git-log` (package `dev.sluide.gitlog`) — `FileHistory` (what a history loads and selects, without
controls), `FileHistoryView` (its table and details) and `AreaTab` (a tab in the header, `FileHistoryView.kt`), opened
by `GitLogView.showHistory`; the commits come from `GitLog.fileHistory` in `modules/git`. Show History in the explorer
and the editor's tabs are hooks of `FileExplorerView` and `EditorArea`, set by `MainWindow`. Decisions in
[ADR 0046](../adr/0046-git-log-file-history.md); the area itself in [git-log.md](git-log.md).

- **Opening a history,** as IntelliJ's History tab:
  - **Show History** in the file explorer's context menu, for files (disabled for folders and the empty area);
  - **Show History** at the end of an editor tab's context menu, for tabs that show a file on disk (not diff tabs,
    terminals or other views);
  - **Show History** in the context menu of the git log's changed files, for files: the history *up to here*, of the
    file's path in the selected commit (the new path of a renamed file) from that commit on; for a deleted file of its
    old path from the commit's first parent on.

  The first two are shown only while the window offers them (always in Thoreau) and are enabled while the window has a
  git log area and the file is below the repository's root. A history from the explorer or a tab starts at `HEAD`.
- **Tabs in the area's header:** while no history is open the header looks as before ("Git Log"). With one, the title
  becomes a row of tabs: `Log` first, then `History: <file name>` per history (tooltip: the path relative to the root,
  plus `up to <short hash>` for a history from a commit), each with × (a middle click closes too). The selected tab is
  in the accent colour, like the log's selected toggles. Clicking a tab selects it and expands a collapsed area;
  clicking elsewhere on the header still expands or collapses it. Opening a history expands the area and selects its
  tab, whose table gets the focus; the same file from the same start again selects its tab. Closing the selected tab
  selects the one left of it; closing the last history goes back to the plain "Git Log" header with the log, unless
  the Commit tab ([commit-and-push.md](commit-and-push.md)) is open, which sits between `Log` and the histories.
  Opening another folder closes all histories. A tab's title is at most 180 px wide, shortened with "…"; when the tabs do not
  fit, they shorten further and the row is cut off at the right, so that Cancel, Fetch and Refresh always stay whole.
- **What the area shows** follows the selected tab: the log as before, or the history. The header's messages, Fetch,
  Cancel and Refresh stay; Refresh, and changes in `.git`, read the log and every open history again (a collapsed area,
  or a tab not shown, only notes it and reads when shown), keeping each history's selected commit if it is still there.
  Only what is shown loads: the log is not read while a history is selected. A header message belongs to the tab it
  was given in: switching tabs clears it, the log's "The git log could not be read." shows only with the Log, and a
  history whose last read failed (with rows kept) says "The history could not be read." again when its tab is selected.
- **The history:** left a table of the commits that changed the file, newest first, following renames (git's
  `--follow`): Subject with the ref labels as in the log (no graph), Author and Date as in the log's table (same
  formats and column behaviour), and Path, the file's path at that commit, only while the listed commits have more than
  one. Merges are not listed (git's `--follow` gives none). At first the row of an *up to here* history's start commit
  is selected (git lists it first, as it changed the file), otherwise the first row. Right, at first
  30 % of the width (the divider can be dragged), the details of the selected commit as in the log, with its branches
  line and hash links.
- **Actions** on the selected row:
  - **Show Diff** (double-click, Enter, Shortcut+D, context menu): what the commit changed in the file against its
    first parent (a root commit against nothing), in the log's diff tab
    ([git-log.md](git-log.md), Show Diff), with the old path of a renamed file; what keeps it from being shown is told
    in the header as there.
  - **Compare with Local** (context menu): the file at that commit (at its path there) against the file in the working
    tree (at the history's path), in the working-tree diff tab of the log's Compare (titled with the commit's short
    hash); a working-tree file that is gone shows as deleted, a commit that deleted the file compares nothing with the
    whole working-tree file. "App.kt is the same in the working tree.", "… is a binary file.", "… is too large to
    compare.", "… is a submodule.", "… cannot be shown as text." and "The diff could not be read." go to the header.
  - **Show in Log** (context menu): selects the Log tab and goes to the commit as Go to does, with the same messages in
    the header (e.g. "1a2b3c4d is not in the log."). A hash link in the history's details does the same.
  - **Copy Revision Number** (context menu, Shortcut+C): the commit's full hash.
  - **Shortcut+G** selects the Log tab and opens Go to Hash/Branch/Tag there. Shortcut+F (the log's filter) is not
    available in a history.
- **Loading:** in pages of 500 in the background, the next page when scrolling within 50 rows of the end, as the log.
  The table says "Loading…", "No history: App.kt has no commits." (untracked, never committed, or no `HEAD` yet) or
  "The history could not be read." (also after 60 s); a refresh that fails with rows shown keeps them and says so in
  the header, and after a page that failed, a refresh reads only the rows loaded before it. Each history reads on a
  thread of its own, so a slow one holds up neither the log nor other histories; its git is stopped when a newer read
  replaces it, its tab closes or another folder opens.
- Not yet implemented: several rows selected (and their combined diff), a history of a folder, the changed files of a
  history's commit, a diff preview, Annotate, Shortcut+W to close a tab.
