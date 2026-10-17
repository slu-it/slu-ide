# Commit and Push

**Module:** `modules/git-log` (package `dev.sluide.gitlog`) — `CommitView` (the tab's tree, message and buttons),
`CommitReader` and `CommitChecks` (what the tab reads and which files are checked, `CommitDraft.kt`, without controls)
and the `CommitChanges` operation (`CommitChanges.kt`, without controls), opened by `GitLogView.showCommit`; the
changes are read and committed by `GitCommits.commitChanges` and `commit` in `modules/git`. The Git menu is in
`MainWindow`. Decisions in [ADR 0048](../adr/0048-commit-tab.md); the area itself in [git-log.md](git-log.md).

- **Opening it,** as IntelliJ's Commit tool window: **Git → Commit…** (Shortcut+K), disabled while the window has no
  git log area. It opens a `Commit` tab in the area's header, right of `Log` and left of the file histories
  ([file-history.md](file-history.md)), selects it, expands the area and puts the focus in the message. Again, it
  selects the tab. The tab closes with its × (or a middle click); the Log is shown then.
- **The draft stays:** the message, Amend and which files are checked are kept while the tab is closed; Git → Commit…
  shows them again. Opening another folder closes the tab and discards the draft.
- **Left, the changes** as a tree with a checkbox on every node, in two groups with their counts:
  - **Changes 4:** the tracked files that differ from `HEAD` (in the index or the working tree), in folders as in the
    log's changed files (compacted, `src/main/kotlin`), with file-type icons and coloured by their change: added green,
    modified blue, deleted grey, a renamed file followed by "← old path". Before the first commit, every file in the
    index, as added.
  - **Unversioned Files 2:** the untracked files that are not ignored, one by one also inside untracked folders, in the
    explorer's red.
  - A group or folder checks or unchecks all files below it; with some of them checked it shows a dash. At first the
    tracked files are checked and the untracked ones are not. The checks are kept by path while the tab reads again: a
    tracked file that shows up later comes checked, an untracked one unchecked.
  - **Space** toggles the selected nodes (all checked: unchecked, else all checked). **Show Diff** (Enter,
    double-click, Shortcut+D, context menu) shows the file against `HEAD` in an editor tab, the live file on the right
    (as Compare with Local in the log, labelled "HEAD"; an untracked file all added). Before the first commit the file
    itself opens. What keeps a diff from being shown ("… is a binary file.") is told in the header. **Open Local File**
    (F4, context menu) and **Copy Path** (context menu) as in the log's changed files. Enter on a folder opens or
    closes it. An untracked nested repository (git lists its folder) has no diff ("sub is a repository.") and does not
    open.
- **Right, the message and its controls:**
  - the message (placeholder "Commit message", monospace). Enter is a new line; **Shortcut+Enter** commits from
    anywhere in the tab;
  - **Amend:** ticked while the message is empty, it fills in the message of `HEAD`; unticked, that message goes again
    if it was not changed. "This commit is already pushed: amending it needs a force push." shows while Amend is
    ticked and `HEAD` is on its upstream. Disabled before the first commit;
  - what keeps git from committing, e.g. "A merge is in progress; finish or abort it in the terminal." or "1 file has
    conflicts; resolve it first.";
  - **Commit**, enabled with a message that is not blank, at least one checked file (or Amend, which then only changes
    the message), nothing in progress, no conflicts and no other operation running;
  - "2 of 5 files selected", or "Nothing to commit." without changes.
- **Committing:** edited files in the repository are offered to be saved first (the commit takes them as on disk).
  Exactly the checked files are committed as they are in the working tree; other staged changes stay staged, other
  local changes stay. The header says "Committing…" (Cancel stops it), then "Committed 1a2b3c4d: Fix the parser." or
  "Amended …". Then the message is cleared (unless it was changed while the commit ran), Amend unticked, and the log
  and the tab read again. A failure (e.g. a hook) shows "Commit Failed" with git's message and keeps the draft;
  untracked files it added stay staged.
- **Reading:** the tab reads in the background, only while it is shown and the area is expanded; changes meanwhile are
  read when it is shown again. It reads again when the window's git status changes (files saved or changed on disk),
  on Refresh and when `.git` changes. While reading, the tree stays. "Loading…", "No changes." and "The changes could
  not be read." show in the tree; a read that fails with a tree shown says so in the header.
- Push: coming next (P14), with a Commit and Push button.
- Not yet implemented: committing parts of a file (chunks or lines), changelists, commit checks, author and sign-off,
  a history of earlier messages.
