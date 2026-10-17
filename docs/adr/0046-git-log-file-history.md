# 0046. File history as a tab of the git log area, read with `git log --follow` from stdin

- Status: accepted
- Date: 2026-10-17

## Context
IntelliJ shows a file's history (Git → Show History) in a History tab of its Git tool window: the commits that changed
the file, following renames, with their details, a diff of each change and a comparison with the local file. The git
log area ([ADR 0035](0035-git-log-area.md)) already lists commits with ref labels and details
([ADR 0037](0037-ref-labels-and-commit-details.md), [ADR 0045](0045-git-log-navigation.md)), opens a commit's diff of a
file ([ADR 0038](0038-changed-files-and-commit-diffs.md)) and a commit's file against the working tree
([ADR 0044](0044-git-log-compare.md)), and has a path filter ([ADR 0039](0039-git-log-filters.md)). Paths may have
characters the JVM cannot pass to a process in a POSIX locale (ADR 0038).

## Decision
- **A tab in the area's header** (the user's choice, as IntelliJ): while histories are open, the header's "Git Log"
  becomes a row of tabs, `Log` first, then `History: <name>` per (path, start commit), closable; the selected tab
  decides what the area shows. Only what is shown loads (the log's `GitLogPages.visible`, the history's
  `FileHistory.visible`).
  Header messages, Fetch, Cancel and Refresh stay the area's; Refresh and `.git` changes reach every history. Another
  folder closes them.
  - Rejected: an editor tab per history. The editor area would need the details pane, paging and the actions of the
    log, and `editor` does not depend on `git-log` (nor should it); the area is where commits are.
  - Rejected: the log's path filter as "history". It does not follow renames (`log.follow=false`, ADR 0039, for
    rewritten parents and the graph), it would replace the user's filters and selection, and there would be one at a
    time.
- **`git log --follow` with the path on standard input:** `GitLog.fileHistory(root, path, start, skip, limit)` runs
  `git --no-optional-locks --literal-pathspecs log --follow -M --name-status --ignore-missing --stdin --root` with the
  log's format (each commit's prefixed by U+001E), `-z`, `--max-count`, and the start revision (`HEAD` or a full hash)
  and the path as `<rev>\n--\n<path>\n` on standard input (`logInput`, as the filters). Checked with git 2.53: git takes
  the pathspec from standard input before it checks that `--follow` has exactly one ("--follow requires exactly one
  pathspec" comes for two paths there, not for one), and follows renames from there. `--ignore-missing` gives an empty
  history while `HEAD` is unborn; an untracked file has none either. `-M` makes the name-status detect renames whatever
  `diff.renames` says; `--follow` itself always detects them, `log.follow` does not matter with `--follow` given.
  `--root`: a `log.showRoot=false` of the user would leave out the root commit's diff, and with it the commit that added
  the file. `--literal-pathspecs`: a file name is no pattern. No `--end-of-options` is needed, as no revision or path is
  an argument. Git's standard input reader strips a trailing carriage return from each line, so a file whose name ends
  with `\r` has no history (as for the log's path filter, ADR 0039).
  - The output is read as NUL-terminated fields in their order (`GitFileHistoryParser`): per commit its header (U+001E,
    then the fields of the log's format), then, if given, the status (after a line break) and one or two paths
    (`GitNameStatusParser`): the path at that commit and, for a rename or copy, the old one. Splitting on U+001E was
    rejected: a subject or path may contain it; only NUL cannot be in either. A commit without a status, which git does
    not give with `--follow` (it lists no merges), would get the path followed at that point; kept as a defence.
- **Threads, timeout, cancellation:** `--follow` turns off git's pruning by paths and its changed-path Bloom filters,
  so git walks every commit up to the page's end; in a large repository that takes long, more so for deep pages. A
  history reads on a thread of its own (`sluide-git-history`, one per tab), not the log's `sluide-git-log`, so a slow
  history holds up neither the log's pages and details nor other histories; the details of a history's commits stay on
  the log's thread (short reads). A page may take up to 60 s (the log's 10 s would fail deep pages of large
  repositories on every refresh). Each read has a `GitCancellation` (ADR 0043): a newer read of the history (a
  refresh), closing its tab and another folder stop the git still running, so that it does not keep the thread or the
  machine busy; closing also stops the thread. A page that failed (also by the timeout) is not counted: a refresh reads
  only the rows loaded before it.
- **Paging re-reads from the start:** `git log --follow --skip=N` counts the skipped commits before following (checked:
  `--skip=1 -n 1` gave the newest change again, `--skip=3 -n 1` nothing), and the skipped commits do not update the
  followed path, so renames across a page boundary were lost. Starting the next page at the last commit's parent with
  the path there (the other way considered) loses commits of merged branches: a branch merged after the last listed
  commit but committed before it is not reachable from that commit's parents (checked with a merge of two commits on a
  side branch). So every page runs `--max-count=skip+limit` from the start and drops the first `skip` entries; the
  result equals one long run, at the price of reading the history again up to each page (quadratic in pages; a file
  rarely has more than a few pages of 500).
- **Reuse:** the rows are `GitLogRow`s without a graph (`GraphRow.NONE`) in the log's Subject cell with ref labels, the
  log's column helpers, resize policy and paging skin; `FileHistory` holds the file's change per commit for the Path
  column (shown only while the paths differ) and the actions. The details are a `CommitDetailsPane` of its own with a
  `CommitDetails` (no changed files) and `CommitBranches` per history, on the log's threads for details and branches.
  Show Diff goes through the log's `showDiff` (`FileDiffs`, `onShowDiff`) with the commit's `GitChangedFile`, so renames
  compare with the old path at the first parent; Compare with Local builds a `GitChangedFile` from the commit's path to
  the working tree's path (renamed, deleted in the working tree, or added for a commit that deleted the file) for
  `workingTreeFileDiff` and `onShowWorkingTreeDiff` (ADR 0044). Show in Log and hash links select the Log and use
  `goToCommit` (ADR 0045).
- **Wiring through `ide`:** `FileExplorerView` and `EditorArea` (its `TabStrip`) get hooks `showHistory` (`null` leaves
  the item out) and `canShowHistory`; `MainWindow` sets them to `GitLogView.showHistory` and to "the area is present
  and the file is below the repository's root". The changed files' "up to here" stays inside `git-log`. No new module
  dependency.
- **The header:** a tab's title is capped at 180 px (ellipsis, the tooltip has the path); the row of tabs may shrink
  below its preferred width and is clipped, while Cancel, Fetch and Refresh keep theirs. Header messages belong to a
  tab: switching clears them, the log's failure shows only with the Log, and a history whose last read failed with rows
  kept says so again when selected. Shortcut+G in a history selects the Log and opens Go to there; Shortcut+F (the
  log's filter bar) has no counterpart in a history.

## Consequences
- Deep pages of a long history read it again from the start each time, walking every commit up to there (no path
  pruning with `--follow`); a file with thousands of commits gets slower page by page, up to the 60 s limit (the log's
  `--skip` has the same shape, ADR 0035). One thread per open history: several histories read in parallel.
- `--follow` follows one path back through history; git's own limits apply (it tracks a single path, renames found by
  similarity, history simplification of merges). Merges are not listed.
- One row selected at a time; no combined diff of several commits, no history of a folder, no Annotate.
- Selecting a history stops loading the log until the Log is selected again; a navigation from a history waits for its
  first page then.
