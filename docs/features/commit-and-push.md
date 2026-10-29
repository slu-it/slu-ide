# Commit and Push

**Module:** `modules/git-log` (package `dev.sluide.gitlog` and its sub-packages, [ADR 0055](../adr/0055-sub-packages-within-modules.md)) — `CommitView` (the tab's tree, message and buttons),
`CommitReader` and `CommitChecks` (what the tab reads and which files are checked, `CommitDraft.kt`, without controls)
and the `CommitChanges` operation (`CommitChanges.kt`, without controls), opened by `GitLogView.showCommit`; the
changes are read and committed by `GitCommits.commitChanges` and `commit` in `modules/git`. The Git menu is made by
`WindowGitLog` in `ide`. Decisions in [ADR 0048](../adr/0048-commit-tab.md); the area itself in [git-log.md](git-log.md).

- **Opening it,** as IntelliJ's Commit tool window: **Git → Commit…** (Shortcut+K), disabled while the window has no
  git log area. It opens a `Commit` tab in the area's header, right of `Log` and left of the file histories
  ([file-history.md](file-history.md)), selects it, expands the area and puts the focus in the message. Again, it
  selects the tab. The tab closes with its × (or a middle click); the Log is shown then.
- **The draft stays:** the message, Amend and which files are checked are kept while the tab is closed; Git → Commit…
  shows them again. Opening another folder closes the tab and discards the draft, and so does a change that turns git
  off until the folder is trusted ([project-trust.md](project-trust.md)).
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
  - what keeps git from committing, e.g. "A merge is in progress; continue or abort it in the log first." or "Files
    have conflicts; mark them resolved in the log first." while files have conflicts outside an operation
    ([stash.md](stash.md));
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
- **Stash Checked Files…** (after Commit and Push…, [ADR 0107](../adr/0107-git-log-stashes.md)) is enabled with at least
  one checked file while no operation runs and nothing blocks committing: the Stash Changes dialog ("Stash 3 checked
  files of 'feature'", a message, no untracked checkbox) stashes exactly the checked files, a renamed file with its old
  path, unversioned ones included ([stash.md](stash.md)); the tab reads its changes again afterwards.
- **Commit and Push…** (right of Commit, Shortcut+Alt+K in the tab, enabled like Commit) commits, and once the commit
  is made opens the Push dialog; a failed or cancelled commit does not.

## Push
`PushDialog` (the window), `PushReader` (what it reads and shows, without controls) and the `PushOperation`
(`PushOperations.kt`, without controls), opened by `GitLogView.showPush`; the target, what is outgoing and the push
itself come from `GitPush` in `modules/git`. Decisions in [ADR 0049](../adr/0049-push.md).

- **Opening it:** **Git → Push…** (Shortcut+Shift+K), disabled while the window has no git log area; the up arrow in
  the log's header ("Push… (Ctrl+Shift+K)", also while collapsed, disabled once the log found no remote); Commit and
  Push. A window of its own titled "Push Commits to origin", owned by the IDE window (so it stays above it) but not
  modal: diffs opened from it can be looked at in the editor. About 900×560, resizable. One per window: opening it
  again brings it to the front; another folder or closing the window closes it. Escape or Cancel closes it.
- **Top row:** `main → origin : main  New`: the checked-out branch; the remote (a choice with several remotes, else
  its name); the target branch, prefilled from git's configuration (the push remote, `remote.pushDefault` or the
  upstream's remote; the branch's own name, as git's default `push.default=simple` pushes it, never an upstream of
  another name such as `main` for a branch created from `origin/main`; the upstream's branch only with
  `push.default=upstream` and when pushing to the upstream's remote) and editable; "New" when the remote
  does not have that branch as of the last fetch (the tooltip names the missing remote-tracking ref). A name git does
  not take ("'a..b' is not a valid branch name.") shows below the row and disables Push. Edits are read after a pause
  of 300 ms; Enter in the field does not push.
- **Left, the outgoing commits:** "All 3 commits" first (selected), then each commit with its subject, author and date,
  newest first; more than 1000 show as "All 1000+ commits" and a last row "and more…". "Loading…", "Nothing to push."
  or "The commits could not be read." instead.
- **Right, the files** of the selected row as in the log's changed files: for All those of `HEAD` against the merge
  base with the remote branch (for a new branch, against the parent of the oldest outgoing commit), for a commit its
  own against its first parent. Show Diff (double-click, Enter, Shortcut+D) opens the diff in an editor tab; what keeps
  one from being shown ("README.md is a binary file.") is said in the dialog. Open Local File (F4) and Copy Path as in
  the log.
- **Notes above the buttons:** "origin/main has 2 commits that your branch does not have; a normal push will be
  rejected. Fetch, then merge or rebase, or force push." when the remote is ahead and there are commits to send (Push
  stays enabled, git answers), with **Pull…** next to it, which opens the Pull dialog with that remote branch chosen
  ([git-log.md](git-log.md), [ADR 0106](../adr/0106-git-log-pull.md); disabled while an operation runs); the Push dialog
  stays open and reads again once the pull is done; "Everything is up to date." when the remote branch is at `HEAD`
  (Push and Force Push disabled); "Your branch is behind origin/main; nothing to push." when `HEAD` is behind it (only
  Force Push, in Push's menu, unless the branch is protected); "As of the last fetch of origin." always: nothing is
  fetched first. A new branch without new commits says "No new commits: pushing creates origin/feature at 1a2b3c4d." in
  place of the commits, and can be pushed. A detached `HEAD` ("HEAD is detached: check out a branch to push it."), no
  commit ("There is no commit to push.") or no remote ("The repository has no remote. Add one in a terminal: git remote
  add <name> <url>.") is said in place of the commits.
- **Push** pushes `HEAD`'s branch to the target and closes the dialog; the first push of a branch without an upstream
  sets it. Disabled for the reasons above, while reading and while another operation runs (the dialog opens all the
  same). The header says "Pushing main to origin/main…" with Cancel, then "Pushed 3 commits to origin/main.", "Pushed
  main to origin/feature (new branch)." or "Everything up to date."; "Push cancelled; it may have reached origin. Fetch
  to check." without a dialog (git is stopped, but the remote may have taken the push already; one stopped after 10
  minutes says the same in its error). A rejection shows
  "Push Failed" with "main could not be pushed to origin/main." and what to do above git's message. The log reads
  again afterwards. No unsaved editors are asked about: a push changes no files.
- **Force Push** (Push's menu) asks first: "Force push feature to origin/feature?" with "2 commits on origin/feature
  that are not in your branch will be lost." (or "origin/feature will be set to 1a2b3c4d."), Cancel the default. It
  replaces the remote branch only if it is still at the commit the dialog showed (missing, for a new branch): "Force
  pushed main to origin/feature.", else rejected as changed since the last fetch. Disabled for `main` and `master` (in
  any case, `Main` too); opening the menu there says "Force push to main is not allowed (protected branch)."
- **Reading:** on the dialog's own thread, newest read wins, again whenever the dialog gains the focus, is opened again
  or an operation (a fetch, a commit) ends; another checked-out branch takes its defaults again.
- Not yet implemented: committing parts of a file (chunks or lines), changelists, commit checks, author and sign-off,
  a history of earlier messages; pushing tags, skipping hooks, several branches at once, Push All up to Here, defining
  remotes.
