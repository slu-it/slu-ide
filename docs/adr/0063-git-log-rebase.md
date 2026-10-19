# 0063. Git log rebases the current branch onto another branch; a stopped rebase is continued, skipped or aborted

- Status: accepted
- Date: 2026-10-19

## Context
IntelliJ rebases the checked-out branch onto any local or remote branch from its log; Thoreau's log only showed a
rebase in progress and left it to the terminal ([ADR 0042](0042-git-log-commit-operations.md)). A plain `git rebase`
is affected by the user's configuration far beyond what the menu item says: `rebase.updateRefs` moves other branches
that point into the rebased commits, `rebase.rebaseMerges` recreates merges, `rebase.autoStash` stashes local changes
and applies them afterwards (possibly with conflicts), and the fork point (`--fork-point`, the default without an
explicit upstream argument) drops commits that the other branch's reflog once had. With the other branch already merged
into the current one, `git rebase` would still run and linearise the history, dropping the merges. A rebase stops at
every conflicting commit and needs continuing, skipping or aborting, also when it was started in a terminal.
Git 2.53 behaves as follows (checked in tests, `GitRebasesTest`):
- the merge backend keeps its state in `rebase-merge/` (`head-name`, `onto`, `msgnum`, `end`), the apply backend
  (`git rebase --apply`) in `rebase-apply/` (`head-name`, `onto`, `next`, `last`), `git am` in `rebase-apply/` too, with
  `applying`; both rebases set `REBASE_HEAD` to the commit they stopped at (also at an `edit` instruction; a `break` or
  a failed `exec` leaves none). `rebase-merge/interactive` is written for every rebase of the merge backend, `git rebase
  --merge` too, so it tells nothing more than the backend;
- with local changes in tracked files, staged or not, `git rebase` refuses ("cannot rebase: You have unstaged changes.")
  and leaves nothing;
- `git rebase --continue` after a resolution that leaves nothing to commit drops that commit with the merge backend
  (also an interactive one); the apply backend refuses ("No changes - did you forget to use 'git add'?") and keeps its
  state, also after a stop without conflicts (untracked files in the way) once they are removed;
- `git rebase --skip` resets the index and the files to `HEAD` (a hard reset), so unstaged changes are lost;
- `git rebase --continue` opens the editor for the commit message of a resolved conflict.

## Decision
- **When it is offered** (the menu, on the JavaFX thread, without running git): for exactly one local or remote branch
  that is not the current one, with a current branch (not detached) that has a commit, the branch at another commit
  than `HEAD`, nothing in progress (the pending bar is empty) and no git write running. A branch behind the other one
  is fast-forwarded by the rebase, which is allowed. Whether the other branch is already contained in the current one
  is checked when it runs, on the write thread (`git merge-base --is-ancestor <onto> HEAD`): then nothing runs and the
  header says "'main' is already contained in 'feature'; nothing to rebase.". Rejected: `git for-each-ref
  --merged=HEAD` with every read of the refs, to disable the menu item, as it walks the whole history on every refresh.
- **Merges dropped:** before a rebase that was not confirmed yet the merge commits only
  the current branch has are counted (`GitRebases.mergeCount`, `git rev-list --count --min-parents=2 <onto>..HEAD`);
  with any, or if that cannot be read, a confirmation (Cancel the default) says "feature has 2 merge commits since
  main; the rebase drops them and replays their changes as plain commits.".
- **The command** (`GitRebases.rebase` in `modules/git`, a singleton bean): `git -c rebase.updateRefs=false -c
  rebase.rebaseMerges=false rebase --merge --no-autostash --no-fork-point --end-of-options <full refname>`: no other
  branch moves, merges are not recreated, local changes are never stashed (git refuses with them, its message is shown,
  as for a checkout in [ADR 0041](0041-git-write-operations.md)), no commit is dropped for the fork point, the merge
  backend whatever `rebase.backend` says, and the refname cannot be taken for an option. Refused without running git
  while git is in the middle of anything (or that cannot be read; the messages of ADR 0042, "A rebase is in progress;
  finish or abort it first.") or when the branch is no longer the current one ("'feature' is no longer the current
  branch."). Up to 10 minutes (`CHECKOUT_TIMEOUT`), like a checkout, as it writes files; not cancellable: stopped by the
  timeout, it leaves its state, which the bar then continues or aborts. Hooks and the rest of the configuration apply.
- **What it gave** is read, not parsed from git's messages: a rebase still in progress afterwards is a stop (with its
  state, below, and git's message; also after git succeeded, e.g. at an `edit` instruction of a rebase started in a
  terminal), none after git succeeded is done (the new `HEAD`), none after a failure is a failure with git's message.
  If what git is in the middle of cannot be read afterwards, git's exit value alone decides between done and failure;
  the refresh after the operation shows the rest.
- **A rebase in progress** (`GitPendingRebase`, replacing the former `GitOperation.REBASE`): `rebase-merge/`, or
  `rebase-apply/` without `applying`, at their `git rev-parse --git-path` (per worktree): the short name of the branch
  (`head-name`, none when the rebase started on a detached `HEAD`), the commit it is rebased onto (`onto`), the commit
  it stopped at (`REBASE_HEAD`), the step and the number of steps (`msgnum`/`end`, `next`/`last`) and the unmerged
  paths. Whether it is interactive is not read (see above). A state file that cannot be read leaves its field empty;
  only the unmerged paths failing makes the whole state unreadable. Rebases started in a terminal (`git rebase`, `-i`,
  `--apply`) are shown and continued, skipped or aborted the same way; `git am`, merges and sequences stay
  terminal-only (ADR 0042).
- **Continue** (`GitRebases.continueRebase`): the checks of continuing a cherry-pick (ADR 0042, now in
  `GitConflictCheck`, shared): conflict markers left refuse it, files that cannot be checked need the confirmation with
  exactly those files; then only the unmerged paths are staged (`git update-index -z --add --remove --stdin`). If a
  conflict was resolved so that nothing is left to commit (the index as `HEAD`, no unstaged changes in tracked files)
  the commit is skipped with `git rebase --skip`, which both backends accept; with unstaged changes it is not, as the
  skip's hard reset would drop them, and git's refusal is shown instead. A stop without conflicts (untracked files in
  the way, a hook) is never skipped by Continue: git tells if there is nothing to commit, as in a terminal, and Skip is
  the user's choice. Otherwise `git rebase --continue` with `GIT_EDITOR=:`: the editor ends at once and the message git
  prepared (the original one, also for the reword or squash steps of an interactive rebase started in a terminal) is
  committed. `GitCli` lets a write give its own `GIT_EDITOR`; `false`, which makes git fail where it would open an
  editor (ADR 0041), stays the default.
- **Skip** `git rebase --skip` and **Abort** `git rebase --abort`, each refused without running git unless a rebase is
  in progress; the outcome is read as above. All four operations ask about unsaved editors first (as in ADR 0042,
  "Rebase", "Continue", "Skip", "Abort").
- **The log's UI** (ADR 0042's bar and dialogs): every stop, also after Continue or Skip, shows the "Rebase Stopped"
  dialog (branch, the commit it stopped at, the conflicted files, git's message in the details; Resolve the default,
  which leaves it to the bar, or Abort Rebase, which aborts without asking again). The pending bar reads "Rebasing
  feature onto 1a2b3c4d: stopped at 5e6f7a8b (3 of 7); 2 files have conflicts." (fields git did not give are left out)
  and has Continue, Skip and Abort, the busy state keeping cherry-pick and revert disabled. Skip and Abort from the bar
  ask first (Cancel the default), as both throw away what was done to resolve conflicts; Continue does not. The
  merges question is the confirmation above ("Rebase", Cancel default). Committing in the log is blocked with "A rebase
  is in progress; continue or abort it in the log first.".
- **Ignored files are not checked** before a rebase, unlike a cherry-pick (ADR 0042): every replayed commit could
  overwrite one, and `git merge-tree` for each of them would cost more than the rebase. Accepted gap: an ignored file a
  rebased commit adds is overwritten without a question, as git does in a terminal.

## Consequences
- The current branch can be rebased onto any branch from the log, without the user's rebase settings changing what
  happens; a rebase that stops is finished from the bar, also one started in a terminal.
- A merged-in branch never linearises the history by accident; merges of the current branch are dropped only after a
  confirmation.
- A rebase cannot be cancelled; after the 10 minutes it is left in progress for the bar.
- Not offered: `--onto`, interactive rebases, `--autostash`, rebasing a branch other than the current one, rebasing onto
  a tag or a commit. An interactive rebase started in a terminal is not told apart from a plain one.
