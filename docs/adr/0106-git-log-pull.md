# 0106. Git log pulls a remote branch into the current one as a fetch, then a merge or a rebase

- Status: accepted; amends [0063](0063-git-log-rebase.md) (a kept autostash is told after Continue, Skip and Abort;
  the fork point only for pulls) and [0061](0061-background-tasks.md) (a task may withdraw its cancel)
- Date: 2026-10-28

## Context
The git log fetches ([ADR 0043](0043-git-log-fetch.md)), merges ([ADR 0105](0105-git-log-merge.md)) and rebases
([ADR 0063](0063-git-log-rebase.md)), and the Push dialog says "Fetch, then merge or rebase, or force push." when the
remote has commits the branch has not ([ADR 0049](0049-push.md)), but pulling needed the terminal. `git pull` fetches
and then merges or rebases as the configuration says (`pull.rebase`, `branch.<b>.rebase`, `pull.ff`, `merge.ff`,
`rebase.autoStash`, `merge.autoStash`), from the branch's upstream or what is named on the command line, and it may ask
for credentials or an ssh passphrase on the terminal while it fetches. With `--rebase` it uses the fork point: commits
the upstream's reflog once had (before a force push rewrote it) are not replayed. Git 2.53 behaves as follows (checked
in tests, `GitPullsTest`):
- `git for-each-ref --format=%(upstream)` gives the remote-tracking ref `branch.<b>.remote`/`.merge` map to through
  the remote's fetch refspecs (`refs/remotes/m/main` for a refspec into `refs/remotes/m/`), nothing without an
  upstream or a refspec that maps it; for a local upstream (`branch.<b>.remote` is `.`) the local branch, with
  `%(upstream:remotename)` `.`;
- `git rebase --fork-point <ref>` takes the fork point from the reflog of `<ref>`, which a fetch writes (`git merge-base
  --fork-point <ref> HEAD` gives the same commit): after the upstream was force-pushed only the commits after the fork
  point are replayed, while `--no-fork-point` replays the commits the force push removed too. Those are left out whoever
  made them, also the user's own commits once pushed and then dropped by someone else's force push, as `git pull
  --rebase` does; `ORIG_HEAD` still points to them afterwards. Without a reflog of `<ref>`
  (`core.logAllRefUpdates=false`) there is no fork point and the rebase starts from the merge base, replaying every
  commit;
- `git rebase --autostash` stashes local changes (`rebase-merge/autostash`, not `refs/stash`) and applies them when
  the rebase ends: at once after a rebase that did not stop, after a stop on `git rebase --continue` once it finishes,
  and on `git rebase --abort`. If applying them conflicts, git exits with 0, stores them as a stash entry
  ("autostash"; "Your changes are safe in the stash.") and leaves the file with the conflict markers of applying them
  and its path unmerged in the index (`UU`), without anything in progress, as for a merge (ADR 0105);
- `git rebase --no-autostash` with local changes in tracked files refuses ("cannot rebase: You have unstaged
  changes."), whatever `rebase.autoStash` says, and changes nothing; `pull.rebase` does not concern `git rebase`.

## Decision
- **A pull is not `git pull`** but the log's fetch of one remote, then its merge or its rebase, so that the fetch is
  never interactive and can be cancelled (ADR 0043), the user's choice in the dialog decides merge or rebase and how,
  not the configuration, and what it gave is read from the repository afterwards (ADRs 0063, 0105), not from git's
  messages. One branch at a time; the tracking configuration is never changed.
- **The dialog** (`PullDialog`, modal, owned by the IDE window, or by the Push dialog when opened there): "Pull into
  'feature'", "From:" a choice of the remote-tracking branches below `refs/remotes/` of a configured remote (the
  longest remote name that starts the short name, as the branches pane finds it, ADR 0043; not `<remote>/HEAD`), sorted
  by short name. Chosen: the branch it was opened for (branches pane, Push dialog), else the current branch's upstream
  if it is one of them, else none, and Pull stays disabled until one is chosen. Merge or Rebase (Rebase chosen every
  time it opens; nothing remembered). Below Merge, indented, the options of `MergeOptions` without their stash
  checkbox, disabled while Rebase is chosen, their message git's for the branch chosen ("Merge remote-tracking branch
  'origin/main' into feature", `defaultMergeMessage`) and following the choice until the user changes it (as Merge
  compares messages). "Stash local changes and restore them afterwards", checked; unchecked and disabled while Merge
  with Squash is chosen (ADR 0105). Pull (the default) or Cancel.
- **Before the dialog**, on the write thread (as Edit Commit Message reads first, ADR 0100; "Reading the branches to
  pull from…"), `GitPulls.sources` reads the current branch, whether it has a commit, the remote branches, the remotes,
  the upstream and what git is in the middle of. No dialog, the header says why instead: what git is in the middle of
  (the messages of ADR 0042), "Pull needs a current branch." (detached), "Pull needs a commit to pull into." (unborn),
  "The repository has no remotes to pull from.", "No remote branches to pull from; fetch first.". While an operation
  runs nothing is read and the header says "Wait for the running git operation to finish." (Git → Pull…; the other
  places are disabled then).
- **Over the Push dialog:** for a pull started there, the Pull dialog and everything the pull asks or answers in a
  dialog (the merges question, "Pull Failed", "Merge Stopped", "Rebase Stopped") are owned by the Push dialog while it
  shows, else by the IDE window, so they are not hidden behind it; why nothing can be pulled, which the header says
  under the Push dialog, is shown in a small "Pull" alert over it as well.
- **The pull** (`PullBranch`, one `RefOperation`; unsaved editors asked first, "Pull Anyway"): `GitRemotes.fetch` of the
  branch's remote (header "Fetching origin…" with Cancel; cancelled: "Pull cancelled.", nothing else; failed: "Pull
  Failed", "origin could not be fetched; nothing was pulled.", git's message). The operation is cancellable during the
  fetch only: once fetched it tells the writer (`RunContext.uncancellable`), which hides Cancel and withdraws the cancel
  of its background task (`BackgroundTask.withdrawCancel`, amending ADR 0061: the task list hides its ✕, and a cancel
  then does nothing, so a later failure is logged as a failure, not as cancelled); a cancel that came too late is
  ignored, as git had finished. Then: a branch that is no longer the current one fails ("'feature' is no longer the
  current branch.", as the merge and rebase would refuse it), the branch gone after the fetch fails ("origin/x no longer
  exists on origin."); already contained in `HEAD` (`merge-base --is-ancestor`): "feature is already up to date with
  origin/main."; Merge: `GitMerges.merge` of the remote-tracking ref as chosen, "Pulled origin/main into feature (merge
  commit)." / "(fast-forward)." / the squash texts of ADR 0105; Rebase: header "Rebasing feature onto origin/main…", the
  merges question of ADR 0063 asked after the fetch (the confirmed pull does not fetch again; the merge commits counted
  from where the rebase starts, the fork point or else the merge base, so merges the fork point leaves out are not asked
  about), then `GitPulls.rebase`, "Pulled origin/main into feature (rebased 3 commits)." (`rev-list --count <ref>..HEAD`
  afterwards) or "(fast-forward)." when `HEAD` was behind only. Commits the fork point leaves out (`rev-list --count
  <ref>..<fork point>`, read before the rebase) are told after it ran, also after a stop: "2 commits that origin/main no
  longer has were left out, as git pull --rebase does; ORIG_HEAD still points to them.". What the fetch changed comes
  first, in the words of the fetch ("Fetched origin: 2 branches updated. …"), when it changed anything; a kept stash is
  told last (ADR 0105's sentence). Stops show "Merge Stopped" or "Rebase Stopped" and leave the pending bar, failures
  "Pull Failed" with git's message.
- **The rebase of a pull** (`GitRebases.rebase` with `forkPoint` and `autostash`, both off by default, so the Rebase
  item of ADR 0063 is unchanged): `git -c rebase.updateRefs=false -c rebase.rebaseMerges=false rebase --merge
  --fork-point --autostash|--no-autostash --end-of-options <full refname>`, with the refusals, the timeout and the
  reading of the outcome of ADR 0063.
- **A kept stash** (amends ADR 0063): `GitRebaseResult.Done.stashKept`, `refs/stash` before and after (ADR 0105; told
  only if both reads succeeded, for the merge too: a failed read tells nothing), read for the rebase and for Continue,
  Skip and Abort, as any rebase started with `--autostash` (also in a terminal) applies its stash when it ends; the
  header then adds "Your local changes could not be restored without conflicts; they are kept in the stash.".
- **Where**: Git → Pull… after Push… (no shortcut), Pull in the log's header between Push and Fetch (Material Design's
  source-pull icon, also while the log is collapsed; disabled while an operation runs, as Fetch, and once the log found
  no remote), "Pull 'origin/main' into 'feature'…" after Merge in the branches pane and the table's branch submenus (one
  remote branch of a known remote, not `<remote>/HEAD`, with a current branch that has a commit, also at `HEAD`'s
  commit, as the fetch may bring more; not while an operation runs or git is in the middle of something; "Pull into
  Current Branch…" otherwise), and Pull… next to the Push dialog's note that the target has commits the branch has not
  (that target's remote-tracking ref chosen; the Push dialog stays open and reads again afterwards).

## Consequences
- A branch behind its remote is brought up to date from the log or the Push dialog, with the user's choice of merge or
  rebase whatever `pull.rebase` says, and a force-pushed upstream does not bring its old commits back.
- `git pull`'s own merge message ("Merge branch 'main' of <url> into feature") is not used: the merge is of the
  remote-tracking ref, so git writes "Merge remote-tracking branch 'origin/main' into feature".
- A force push by someone else that dropped commits the user had pushed drops them locally too on a pull with Rebase,
  as with `git pull --rebase`; the header says how many and that `ORIG_HEAD` still has them, so they can be recovered
  (`git cherry-pick`, a branch at `ORIG_HEAD`) until the next operation moves `ORIG_HEAD`. A pull with Merge keeps
  them.
- The background task of a pull loses its ✕ once fetched, as the header loses Cancel.
- Not offered: pulling several branches or all remotes at once, `git pull`'s octopus merges, pulling a tag or a local
  branch, `--rebase=merges`/`interactive`, setting the upstream, updating local branches other than the current one.
