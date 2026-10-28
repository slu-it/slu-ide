# 0105. Git log merges a branch into the current one; a merge in progress is continued or aborted

- Status: accepted; amends [0042](0042-git-log-commit-operations.md) (merges are continued and aborted from the bar)
- Date: 2026-10-28

## Context
The git log rebases, cherry-picks, reverts and resets ([ADR 0063](0063-git-log-rebase.md), [ADR
0042](0042-git-log-commit-operations.md)), but merging needed the terminal, and so did any merge that stopped with
conflicts, also one `git pull` started: the bar said "A merge is in progress; finish or abort it in the terminal."
without buttons. A plain `git merge` is shaped by the user's configuration: `merge.ff` (`false` always makes a merge
commit, `only` refuses one), `merge.autoStash` (stashes local changes and applies them afterwards), `merge.log` (adds
the merged commits' subjects to the message) and `merge.suppressDest` (which branches get no " into <branch>" in the
message; `main` and `master` by default). Git 2.53 behaves as follows (checked in tests, `GitMergesTest`):
- the message names the branch as it was given: `git merge refs/heads/main` writes "Merge branch 'refs/heads/main'";
  a short name that is also a tag's merges the tag (with a warning), and `git rev-parse --symbolic-full-name <name>`
  prints nothing for such an ambiguous name (the first match if `core.warnAmbiguousRefs` is off);
- `git merge -F -` does not read the message from its standard input ("could not read file '-'"); `--file=<file>`
  does;
- `--squash` is refused with the `--no-ff` that `merge.ff=false` gives ("options '--squash' and '--no-ff.' cannot be
  used together"); `--ff --squash` is not. A squash that conflicts leaves the conflicts and `SQUASH_MSG`, but no
  `MERGE_HEAD`: no merge is in progress;
- with `--autostash` local changes are stashed (`MERGE_AUTOSTASH`) and applied when the merge ends: at once after a
  fast-forward or a merge commit, and for a merge that stopped on `git merge --continue`, `git merge --abort` or a
  `git commit` that concludes it. A squash, also a clean one, keeps them stashed until the next commit (exit 0, "When
  finished, apply stashed changes with `git stash pop`", the files back at their committed content, nothing in the
  stash list); without the stash, changes the squash does not touch simply stay. If applying them conflicts,
  git exits 0, stores them as a stash entry ("autostash"; "Your changes are safe in the stash.") and leaves the files
  with the conflict markers of applying them and their paths unmerged in the index (`UU`), without anything in
  progress. `--no-autostash` overrides `merge.autoStash`; without a stash, git refuses local changes the merge would
  overwrite and changes nothing;
- `git merge --continue` with `GIT_EDITOR=:` commits `MERGE_MSG` without its `# Conflicts:` comment lines; an octopus
  merge (`git merge a b`) has one line per commit in `MERGE_HEAD`.

## Decision
- **When it is offered** (the menu, on the JavaFX thread, without running git): exactly as Rebase (ADR 0063), for one
  local or remote branch that is not the current one, with a current branch (not detached) and a `HEAD` commit, the
  branch at another commit than `HEAD`, nothing in progress and no git write running: "Merge 'main' into
  'feature'…" ("Merge into Current Branch…" without a current branch or such a selection) after the Rebase item, in
  the branches pane and in the commit table's branch submenus, which share the commands. Not for tags, commits or the
  Git menu.
- **The dialog** (`MergeDialog`, modal, owned by the window): "Merge 'main' into 'feature'", the options of
  `MergeOptions` (a component of its own, which the Pull dialog of the next round embeds below its Merge choice with
  a stash checkbox of its own): Fast-forward if possible (preselected, `--ff`), Always create a merge commit
  (`--no-ff`), Fast-forward only (`--ff-only`), Squash (`--ff --squash`), the message in a monospace area prefilled
  with [`defaultMergeMessage`](../../modules/git/src/main/kotlin/dev/sluide/git/GitMergeResult.kt) (git's message
  without `merge.log` and with the default `merge.suppressDest`: "Merge branch 'x'", "Merge remote-tracking branch
  'origin/x'", " into <branch>" unless that is `main` or `master`; compared with a real `git merge --no-ff --no-edit` in
  a test), disabled for Fast-forward only and Squash, and "Stash local changes and restore them afterwards", checked,
  unchecked and disabled for Squash. Merge (the default) or Cancel. The message goes to git only if it was changed (ignoring leading
  and trailing blank lines and trailing spaces, as Edit Commit Message compares, [ADR
  0100](0100-editing-commit-messages.md)) and is not blank; otherwise git makes its own, so its configuration applies.
- **The command** (`GitMerges.merge` in `modules/git`, a singleton bean next to `GitRebases`): `git merge
  --ff|--no-ff|--ff-only|--ff --squash --autostash|--no-autostash --no-edit [--cleanup=whitespace --file=<file>]
  --end-of-options <name>`, the mode and the stash always explicit, so `merge.ff` and `merge.autoStash` do not change
  what the dialog said; a squash always with `--no-autostash` (above: git would leave the changes stashed, and the local
  changes it does not touch stay anyway; it refuses those it would overwrite). The name is the branch's short name
  (`main`, `origin/main`), so that git's message reads as in a terminal, unless it also stands for another ref; then the
  full refname with the default message, so the branch is merged and not the tag. An edited message is written to a
  temporary file as UTF-8 and git is told so (`-c i18n.commitEncoding=UTF-8`, as for a commit, [ADR
  0048](0048-commit-tab.md)); the file's path must be passable in the JVM's locale ([ADR
  0038](0038-changed-files-and-commit-diffs.md)). Refused without running git while git is in
  the middle of anything (or that cannot be read; "A merge is in progress; finish or abort it first." and the others
  of ADR 0042) or when the branch is no longer the current one ("'feature' is no longer the current branch."). Before
  it, on the write thread, `git merge-base --is-ancestor <ref> HEAD`: if the branch is already contained nothing runs
  ("'main' is already contained in 'feature'; nothing to merge."). Up to 10 minutes (`CHECKOUT_TIMEOUT`), as it
  writes files; not cancellable. Hooks and the rest of the configuration apply.
- **What it gave** is read, not parsed from git's messages (as ADR 0063): a merge in progress afterwards is a stop
  (`GitMergeResult.Stopped` with git's message), none after git succeeded is done (`Done`: fast-forward if the new
  `HEAD` is the merged commit; squashed if a squash exited with 0, or with 1 leaving conflicts, and wrote
  `SQUASH_MSG` anew, not one an earlier squash left, then also with the conflicts it left), none
  after a failure is a failure with git's message. `Done` also tells `stashKept`: `refs/stash` (`git rev-parse -q
  --verify`) differs from before the command, which only git storing an autostash it could not apply does; read for
  merge, Continue and Abort alike. The header says "Fast-forwarded feature to main.", "Merged main into feature.",
  "Squashed main into the index of feature; commit the changes." ("… with conflicts in 2 files; resolve them, then
  commit."), followed by "Your local changes could not be restored without conflicts; they are kept in the stash."
  where that applies. Failures (Fast-forward only not possible, local changes in the way without the stash) open
  "Merge Failed" with git's message.
- **A merge in progress** (`GitPendingMerge`, replacing the former `GitOperation.MERGE`): `MERGE_HEAD` at its `git
  rev-parse --git-path` (per worktree), after a rebase and `git am`, before a sequence and a single cherry-pick, as
  before: the commits being merged (`MERGE_HEAD`'s lines), the first line of `MERGE_MSG` that is not blank or a
  comment, and the unmerged paths. A file that cannot be read leaves its field empty; only the unmerged paths failing
  makes the state unreadable. Merges started in a terminal or by `git pull` are shown the same way.
- **The log's UI**: a stop opens "Merge Stopped" ("Merge of main into feature stopped with conflicts in:", the paths,
  git's message under Show Details; Resolve the default, Abort Merge aborts without asking again) and the header says
  "Merge of main into feature stopped with conflicts.". The bar reads "Merge branch 'main' into feature: 2 files have
  conflicts." ("…: conflicts resolved.", "A merge is in progress: …" without a message) with Continue and Abort, its
  busy state keeping Rebase, Merge, Cherry-Pick and Revert disabled. An octopus merge says "An octopus merge is in
  progress; finish or abort it in the terminal." without buttons. **Continue** (`GitMerges.continueMerge`) runs the
  checks of ADR 0042 through `GitConflictCheck` (markers left refuse it, unchecked files need the confirmation), stages
  exactly the unmerged paths, then `git merge --continue` with `GIT_EDITOR=:`, which keeps git's message: "Merged main
  into feature." with the branches the refs read with the bar name (the local or remote branch at `MERGE_HEAD`, the
  current branch), "Merge committed." where they are not known. **Abort** asks first ("Abort the merge? Its changes
  are undone, also what was done to resolve its conflicts.", Abort Merge or Cancel, the default), then `git merge
  --abort`: "Aborted the merge.". Merge, Continue and Abort ask about unsaved editors first ("Merge", "Continue",
  "Abort"). The Commit tab is blocked with "A merge is in progress; continue or abort it in the log first."; a reset
  still warns "A merge is in progress; resetting abandons it.".
- **Ignored files are not checked** before a merge, unlike a cherry-pick (ADR 0042), as for a rebase (ADR 0063): an
  ignored file the merged branch adds is overwritten without a question, as git does in a terminal. Accepted gap.

## Consequences
- Any local or remote branch is merged into the current one from the log, the mode and the stash as the dialog says
  whatever the configuration; a merge that stops is finished from the bar, also one from a terminal or `git pull`.
- Local changes survive a merge by default; when git cannot apply them back, the header says where they are, and the
  files show the conflicts of applying them, which the user resolves (the stash entry stays until dropped).
- A squash that conflicts leaves its conflicts without a merge in progress: no bar, and the Commit tab stays blocked
  until the files are staged in a terminal (`git add`), as for any unmerged path outside an operation.
- An edited message is committed as UTF-8 by the merge itself, but if the merge stops and is continued, `git merge
  --continue` commits `MERGE_MSG` under the user's `i18n.commitEncoding`: with another encoding configured (e.g.
  ISO-8859-1), an edited non-ASCII message gets the wrong encoding header. Continue does not force UTF-8, as the
  `MERGE_MSG` of a merge started in a terminal is in the user's encoding. Accepted gap.
- Not offered: merging tags or commits, octopus merges from the log (only shown), `--no-commit`, strategies and their
  options (`-s`, `-X`), merging into a branch other than the current one.
