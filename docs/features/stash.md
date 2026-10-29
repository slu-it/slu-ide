# Stashes

Local changes are put aside and brought back from the git log, the Git menu and the Commit tab
([ADR 0107](../adr/0107-git-log-stashes.md)); conflicts git leaves without anything in progress are marked resolved from
the log's bar. Git commands, threads and the question about unsaved editors are those of the git log
([git-log.md](git-log.md)).

- **Stashes group:** in the git log's branches pane, after Tags, a group "Stashes" while there are stashes: one node per
  stash, newest first, named by its message as `git stash list` gives it ("On feature: message", "WIP on feature:
  1a2b3c4 subject", "WIP on (no branch): …" for one made on a detached `HEAD`). Hovering one shows "stash@{0}, made
  <date>". Read with every refresh of the expanded log. The search field filters them by message. Selecting or
  double-clicking a stash neither selects a commit nor filters the log (stash commits are not in it).
- **Menu of a stash:** "Pop Stash", "Apply Stash", "Drop Stash…", "Copy Message" and "Stash Changes…" (on the Stashes
  group only Stash Changes… is enabled); the branch items are not there. Nothing that changes the repository while a
  git operation runs; Pop, Apply and Stash Changes… not while the bar above the table shows something (a merge, rebase,
  cherry-pick, or files with conflicts).
  - **Pop / Apply:** asks about unsaved editors first ("Pop Anyway", "Apply Anyway"), then `git stash pop` / `apply` of
    that stash, without `--index` (what was staged comes back unstaged; after conflicts git leaves the files it merged
    cleanly staged). "Popped stash@{0} (On feature: message)." / "Applied …"; Apply keeps the stash in the list. If the
    list changed since it was read (`stash@{0}` is now another stash) nothing runs: "The stash list changed; check it
    again.". Local changes in the way: "Pop Failed" / "Apply Failed" with git's message, nothing changed, unless the
    stash has untracked files: git restores those first. Done in part (also: an untracked file of the stash exists
    already, then git applies the rest), the stash is kept and the header and the error dialog say what was done above
    git's message: "Popped stash@{0} only partly: its changes to tracked files were applied; untracked v.txt was
    restored; untracked u.txt could not be restored as it already exists. The stash is kept." or "…: its changes to
    tracked files were not applied; untracked u1.txt was restored. …".
  - **Conflicts:** the dialog "Stash Conflicts" lists the files ("Popping stash@{0} gave conflicts in:") and says "The
    stash is kept; drop it once they are resolved." ("The stash stays in the list; …" for Apply); OK closes it. The
    header says "Popping stash@{0} gave conflicts in 2 files; the stash is kept.". The files keep git's conflict
    markers; the bar then offers Mark Resolved (below).
  - **Drop Stash…:** asks "Drop stash@{0} (On feature: message)? Its changes are lost." (Drop, or Cancel, the default);
    "Dropped stash@{0}.". Also while the bar shows something; no question about editors (no file changes).
  - **Copy Message** copies the message as listed.
- **Stash Changes dialog** (Git → Stash Changes…, the Stashes menu): "Stash local changes of 'feature'" ("…of HEAD"
  when detached), a one-line message ("Message (optional)"; empty: git's "WIP on …"), "Include untracked files"
  (unchecked; ignored files never go along), Stash (the default) or Cancel. Unsaved editors are asked about first
  ("Stash Anyway"). "Stashed local changes as stash@{0}." or "No local changes to stash.". When something is in
  progress, the header says why instead of the dialog. A message with characters the JVM cannot pass to git in its
  locale (non-ASCII in a POSIX locale) is refused with an error; stash without one then.
- **Git menu:** after Pull…, a separator, "Stash Changes…" and "Pop Latest Stash" (no shortcuts), disabled like the
  other Git items while the git log has no repository. Pop Latest Stash reads the stashes first, also with the log
  collapsed: "No stashes to pop." without one, else the pop of `stash@{0}` as above.
- **Commit tab:** "Stash Checked Files…" next to the commit buttons, enabled with at least one checked file while no
  operation runs and nothing blocks committing: the same dialog, "Stash 3 checked files of 'feature'", without the
  untracked checkbox; exactly the checked files are stashed (a renamed file with its old path too), unversioned ones
  included. The tab reads its changes again afterwards ([commit-and-push.md](commit-and-push.md)).
- **Mark Resolved:** when files have conflicts but git is in the middle of nothing else (a conflicting pop or apply, a
  squash merge with conflicts, local changes a merge or pull could not restore), the bar above the commit table says "2
  files have conflicts." ("1 file has conflicts.") with Mark Resolved. It asks about unsaved editors ("Mark Resolved
  Anyway"), refuses while conflict markers are left ("Conflict markers are left in: a.txt"), asks before files it cannot
  check for markers (binary, deleted, submodules: "Mark them resolved as they are on disk?", "…will be staged"), then
  stages exactly those files, nothing else: "Marked 2 files resolved.". Until then merges, rebases, cherry-picks, pulls,
  stashing and committing are refused ("2 files have conflicts; resolve them and mark them resolved first."; the Commit
  tab: "Files have conflicts; mark them resolved in the log first.").
- **Not noticed at once:** a `git stash drop` in a terminal (of other than the only stash) shows after the next refresh
  or change in `.git`; a stash or pop in a terminal shows at once.
- **Not offered:** keep-index, `--index`, a branch from a stash, showing a stash's changes, dropping all stashes,
  stashing parts of files.
