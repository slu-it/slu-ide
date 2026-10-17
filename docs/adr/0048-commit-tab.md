# 0048. Committing in a Commit tab of the git log area, with `git commit --only` and a pathspec file

- Status: accepted
- Date: 2026-10-17

## Context
Thoreau could read history and change refs ([ADR 0041](0041-git-write-operations.md),
[ADR 0042](0042-git-log-commit-operations.md), [ADR 0043](0043-git-log-fetch.md)), but not commit. IntelliJ commits
from its Commit tool window: a tree of the changed and unversioned files with checkboxes, a message, Amend, and Commit
(Ctrl+K). Thoreau has no staging area in its UI and no changelists. Paths may have characters the JVM cannot pass to a
process in a POSIX locale ([ADR 0038](0038-changed-files-and-commit-diffs.md)), and the message is free text with any
characters. The git log area already has header tabs ([ADR 0046](0046-git-log-file-history.md)), a write thread with
error dialogs and the question about unsaved editors (ADRs 0041, 0042), and working-tree diffs with the live file on the
right ([ADR 0044](0044-git-log-compare.md)).

## Decision
- **A tab of the git log area** (the user's choice, as IntelliJ's Commit tool window): **Git → Commit…** (Shortcut+K)
  opens a closable `Commit` tab in the area's header, expands the area and focuses the message. Left a tree of the
  changes with checkboxes ("Changes" and "Unversioned Files", each with its count, folders and groups tri-state); right
  the message, Amend, Commit and how many files are checked.
  - Rejected: a non-modal window of its own (IntelliJ's float mode). It would need its own theming, placement and
    closing with the IDE window, as the working-tree windows of ADR 0044 do, for no gain over a tab next to the log.
  - Rejected: a modal dialog (IntelliJ's old commit dialog). It blocks the editor while writing the message, and diffs
    opened from it could not be looked at.
- **The git commands** (`GitCommits.commitChanges` and `commit` in `modules/git`):
  - tracked changes against `HEAD`: `git diff -z --name-status -M HEAD` (renames detected whatever `diff.renames`
    says);
  - while `HEAD` is unborn (no commit yet): every index entry, `git ls-files -z`, as added;
  - untracked files, not ignored: `git ls-files -z --others --exclude-standard`, each file also inside untracked
    folders; a nested repository is listed as its folder with a trailing `/` (committing it records a gitlink without a
    `.gitmodules`);
  - checked untracked files are staged first: `git --literal-pathspecs add --pathspec-from-file=<file>
    --pathspec-file-nul`;
  - then `git --literal-pathspecs -c i18n.commitEncoding=UTF-8 commit --only [--amend] --cleanup=whitespace -F -
    --pathspec-from-file=<file> --pathspec-file-nul`. The message goes to standard input as UTF-8, whatever
    `i18n.commitEncoding` says. Standard input carries the message, so the paths go in a temporary file of
    NUL-terminated UTF-8 names (never arguments, ADR 0038); only that file's own name must be passable, and it is
    deleted afterwards. `--cleanup=whitespace` keeps lines starting with `#` (no editor strips comments here) and only
    trims whitespace and blank lines, as for tag messages (ADR 0041);
  - `--literal-pathspecs`: a file name is no pattern;
  - a renamed file is committed with both of its paths, so that the deletion of the old path goes along; a copy only
    with its new path, as its source did not change;
  - the message of `HEAD` for Amend: `git show -s --no-show-signature --encoding=UTF-8 --format=%B HEAD`; whether
    `HEAD` is pushed: it is `@{upstream}` of the current branch or an ancestor of it.
- **What `--only` means:**
  - exactly the checked files are committed, as they are in the working tree, whatever is staged for them;
  - other staged changes stay staged, other local changes stay as they are: the index is restored for all paths but
    the committed ones;
  - Amend without checked files only changes the message and keeps the tree (`commit --amend --only` without paths);
  - the untracked files a failed commit added (a hook refused it) stay staged; nothing is rolled back.
- **Verified with git 2.53** (`GitCommitChangesTest`, against real repositories): only the checked files are committed
  and other changes are left alone; a partly staged file is committed with its working-tree content; checked untracked
  files are added and the others left; a deleted file, a staged deletion, both sides of a rename, and the path of a
  copy but not its source; Amend with files keeping the other changes, and Amend of only the message keeping the tree;
  the first commit of an unborn `HEAD`, and Amend refused without a commit; a non-ASCII path with a non-ASCII message;
  lines starting with `#` kept and whitespace cleaned up; a failing hook's message reported, with the added files left
  staged; a nested repository listed as its folder and committed as a gitlink; ignored files left out; whether `HEAD`
  is pushed; `HEAD`'s message read as UTF-8 without signature lines whatever the log configuration says; nothing run
  when already cancelled.
- **Refused without running git:** a blank message; nothing checked (unless Amend); Amend without a commit; anything
  git is in the middle of (a merge, rebase, `git am`, cherry-pick or revert, single or a sequence, or when that cannot
  be read); unmerged paths (conflicts), also without anything in progress. The tab says so above Commit ("A merge is in
  progress; finish or abort it in the terminal.", "2 files have conflicts; resolve them first.") and Commit is
  disabled; it is also disabled without a message, without a checked file (unless Amend) and while another operation
  runs.
- **Threads:** the tab reads its changes, `HEAD`'s message and its diffs on a thread of its own, `sluide-git-commit`
  (newest read wins; only while the tab is shown and the area expanded, a reload asked for meanwhile runs when it is
  shown again). The commit is a `RefOperation` (`CommitChanges`) on the log's `sluide-git-write` (`RefWriter`), so it
  never runs together with a checkout, fetch or reset; the header says "Committing…", then "Committed 1a2b3c4d:
  <subject>."; a failure shows "Commit Failed" with git's message, a hook's output included. Afterwards the log and the
  tab read again. The tab also reads again when the window's git status changes (`GitLogView.workingTreeChanged`, from
  `MainWindow`), on Refresh and on changes in `.git`.
- **Timeout and cancelling:** a commit may take 10 minutes (hooks, a GPG pinentry). It is cancellable with the header's
  Cancel, like a fetch (ADR 0043): git removes its lock files on SIGTERM and moves the branch last, so a stopped commit
  leaves the branch where it was; "Commit cancelled." without a dialog, the draft kept. The `git add` of the checked
  untracked files runs first and is not cancellable (60 s, as other ref writes, ADR 0041): after a cancelled or failed
  commit those files stay staged.
- **Unsaved editors** are asked about first ("Commit", `beforeFileChange`, ADR 0042): the commit takes the files as
  they are on disk.
- **Header tabs and drafts:** the tabs are `Log`, then `Commit` while open, then the file histories; the row of tabs
  shows whenever a tab besides the Log is open. The selected tab is one of Log, Commit or a history. The tab's view
  and its draft (message, Amend, which files are checked) stay in memory after the tab is closed, for the window's
  lifetime, so opening it again shows the same draft. Another folder closes the tab and discards the draft. Which
  files are checked is kept by path: a tracked file not seen before is checked, an untracked one is not; a successful
  commit forgets the committed paths, clears the message and unticks Amend.
- **Show Diff** (Enter, double-click, Shortcut+D) shows the file against `HEAD` with the live file on the right, as
  Compare with Local does (ADR 0044), labelled "HEAD"; an untracked file is all added. Without a commit (unborn `HEAD`)
  there is nothing to compare with, and the file itself is opened. An untracked nested repository has no diff ("sub
  is a repository." in the header) and is not opened.
- **Shortcuts:** Shortcut+K opens the tab (IntelliJ's); Shortcut+Enter commits from anywhere in the tab, Enter is a new
  line in the message. On Linux a terminal with the focus sends Ctrl+K to the shell instead
  ([terminal.md](../features/terminal.md)). Shortcut+G in the tab goes to the Log's Go to, as from a history; Shortcut+F has no meaning there.

## Consequences
- A user without a terminal can commit chosen files, amend and reword, with hooks and signing as configured.
- Whole files only: no partial commits of chunks or lines (a future item); no changelists; no commit checks
  (inspections, tests before committing), author, sign-off or skipping hooks; no history of earlier messages.
- Whatever else is staged stays staged and goes into the next commit made in a terminal, as with IntelliJ.
- Push comes in the next package (P14): the Push dialog, force push and a Commit and Push button, for which the row of
  buttons keeps room.
- One more thread per window while the tab exists.
