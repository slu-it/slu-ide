# 0107. Git log stashes local changes, pops, applies and drops stashes, and marks conflicts resolved from the bar

- Status: accepted; amends [0105](0105-git-log-merge.md) (a squash's conflicts are marked resolved from the bar) and
  [0042](0042-git-log-commit-operations.md) (unmerged paths outside an operation show in the bar) and
  [0062](0062-git-concurrency.md) and [0013](0013-git-status.md) (`.git/refs` is watched too)
- Date: 2026-10-28

## Context
Merging and pulling stash local changes with git's `--autostash` ([ADR 0105](0105-git-log-merge.md), [ADR
0106](0106-git-log-pull.md)) and keep them as a stash entry when they cannot be applied back, but stashing by hand,
popping, applying and dropping needed the terminal, and so did the conflicts git leaves without anything in progress:
those of a squash, of an autostash it could not apply, of a conflicting `git stash pop`. The Commit tab then said "2
files have conflicts; resolve them first." and nothing in the log helped. Git 2.53 behaves as follows (checked in
tests, `GitStashesTest`):
- `git stash list -z --format=%gd%x1f%H%x1f%ct%x1f%gs` lists `stash@{n}` (newest first, also with `log.date` set), the
  stash commit, its committer time and the reflog message: "On feature: message", "WIP on feature: 1a2b3c4 subject",
  "WIP on (no branch): …" for one made on a detached `HEAD`, non-ASCII as written;
- `stash@{n}` is a place in the reflog of `refs/stash`: every stash made or dropped shifts the others, so a name read
  with the log may stand for another stash when the user acts (`git rev-parse -q --verify stash@{n}^{commit}` tells);
- `git stash pop` and `apply` exit 1 for conflicts (the files keep git's markers, their paths unmerged, `UU`, nothing
  in progress, and the files it merged cleanly are left staged, `M `), when git refuses (local changes in the way)
  and when an untracked file of the stash exists already ("u.txt already exists, no checkout", "could not restore
  untracked files from stash"). Git first writes the stash's untracked files that are not on disk (of a stash made
  with `--include-untracked`), so a refusal changes nothing only for a stash without them; then it applies the tracked
  changes unless local changes are in the way, and fails on an untracked file that exists already after them. In all
  of these the stash is kept ("The stash entry is kept in case you need it again."). Without
  `--index` and without conflicts what was staged comes back unstaged. `apply` also takes a stash commit by its hash;
  pop, apply and `drop` take `--end-of-options`;
- `git stash push` with nothing to save exits 0 ("No local changes to save") and leaves `refs/stash` where it was;
  `--include-untracked` adds the untracked files that are not ignored, ignored files never go along;
  `--pathspec-from-file=- --pathspec-file-nul` reads the paths from standard input (with `git --literal-pathspecs`, a
  file name is no pattern), and an untracked path among them goes along with `--include-untracked`;
- `git stash push` has no option to read the message from a file or standard input, only `-m`/`--message`, an argument
  that a JVM in a POSIX locale cannot pass unchanged ([ADR 0038](0038-changed-files-and-commit-diffs.md));
- `git stash push` and `pop` write the index, which the watch of `.git` notices ([ADR 0062](0062-git-concurrency.md));
  `git stash drop` of an entry other than the only one changes only `.git/refs/stash` and its reflog, below the watched
  folder.

## Decision
- **Backend** (`GitStashes` in `modules/git`, a singleton bean): `list` as above; `push(message, includeUntracked,
  paths?)` (`git [--literal-pathspecs] stash push [--include-untracked] [--message=<m>] [--pathspec-from-file=-
  --pathspec-file-nul]`, 10 minutes as it writes files); `pop` (`git stash pop --end-of-options stash@{n}`) and
  `apply` (`git stash apply <commit>`), never `--index`, 10 minutes; `drop` (`git stash drop --end-of-options
  stash@{n}`, 60 s). Each first checks that `stash@{n}` still names the commit listed, else nothing runs (`Changed`:
  "The stash list changed; check it again."). Apply is then given that commit, so nothing can shift it; pop and drop
  need the reflog entry by name, so a stash made or dropped elsewhere in the milliseconds between the check and git
  would still shift it (accepted). Results: `Done`, `NothingToStash` (`refs/stash` did not move), `Conflicts(paths,
  kept)` (git failed and paths are unmerged afterwards; kept if `stash@{n}` still names the stash), `Partly(tracked,
  restored, inTheWay, kept, message)` (git failed, nothing unmerged, but it changed the stash's files: one of the
  tracked paths it changes, `git diff --name-only <commit>^1 <commit>`, has another state in `git status
  --porcelain=v2 --untracked-files=no` than before, or one of its untracked files, `git ls-tree <commit>^3`, that was
  not on disk before is there now; edits of other files meanwhile, an editor saving, do not count. `tracked` tells
  whether the tracked changes were applied, `null` for a stash without; `inTheWay` are its untracked files that were on
  disk before. Existence is checked by the JVM, for paths it cannot name with `git ls-files --others`), `Changed`,
  `Failed` with git's message. Push, pop and apply are refused while git
  is in the middle of anything, now including conflicts alone (below); a drop is not, as it changes no files. A
  message the JVM cannot pass is refused ("'Grüße' cannot be passed to git in this locale."), never mangled.
- **Stashes in the branches pane:** a group "Stashes" after Tags, only with stashes, one node per stash newest first,
  named by its message as git lists it, an archive icon, the tooltip "stash@{0}, made <date>" (the date as the table
  shows it). Read with every first page of the expanded log, after the refs, with one `git stash list`. The search
  filters them by message; selecting or double-clicking one neither navigates nor filters the log (stash commits are not
  in it). The selection is kept across refreshes by the stash's commit.
- **Their menu:** for a selection of stashes and the Stashes group only, the context menu has only "Pop Stash",
  "Apply Stash", "Drop Stash…", "Copy Message" and "Stash Changes…"; the ref items are not there (not merely disabled,
  as for groups: a stash has no ref). Pop, Apply, Drop and Copy Message need exactly one stash; nothing that changes the
  repository while a git write runs; Pop, Apply and Stash Changes not while the bar shows something. Pop and Apply ask
  about unsaved editors ("Pop Anyway", "Apply Anyway"); done: "Popped stash@{0} (On feature: message)." / "Applied
  …". Conflicts open "Stash Conflicts" ("Popping stash@{0} gave conflicts in:", the paths, "The stash is kept; drop it
  once they are resolved.", for an apply "The stash stays in the list; …"; OK only, there is nothing to abort) and the
  header says "Popping stash@{0} gave conflicts in 2 files; the stash is kept.". Done in part, the header
  and "Pop Failed" / "Apply Failed" say what was done above git's message: "Popped stash@{0} only partly: its changes
  to tracked files were applied; untracked v.txt was restored; untracked u.txt could not be restored as it already
  exists. The stash is kept." (local changes in the way: "…: its changes to tracked files were not applied; untracked
  u1.txt was restored. …"). Git refusing opens "Pop Failed" / "Apply
  Failed" with git's message. Drop asks "Drop stash@{0} (On feature: message)? Its changes are lost." (Drop,
  Cancel the default), no question about editors: "Dropped stash@{0}.".
- **Stash Changes dialog** (Git → Stash Changes…, the Stashes menu): read first on the write thread (the current
  branch, what git is in the middle of; the header says why if nothing can be stashed), then "Stash local changes of
  'feature'" ("…of HEAD" when detached), a one-line message ("Message (optional)"; blank means git's own), "Include
  untracked files" unchecked, Stash (the default) or Cancel; unsaved editors asked first ("Stash Anyway"). "Stashed
  local changes as stash@{0}." or "No local changes to stash.". Keep-index is not offered.
- **Git → Pop Latest Stash:** reads the stashes on the write thread (so it works with the log collapsed): "No stashes to
  pop." without one, else the pop of `stash@{0}` as above, asking about editors once the stash is known.
- **Commit tab:** "Stash Checked Files…" after Commit and Push…, enabled with a checked file while no operation runs
  and nothing blocks committing (git refuses a stash then too).
  The same dialog, "Stash 3 checked files of 'feature'", without the untracked checkbox: the checked files are given as
  the pathspec on git's standard input (a renamed file with both of its paths, as a commit takes them), with
  `--include-untracked` when one of them is unversioned. The tab reads again after it, as after any write.
- **Conflicts without an operation** (`GitPendingConflicts`, a new kind of `GitInProgress`): read by `inProgress` only
  when nothing else is in progress and `git ls-files -u` lists paths (one more call per refresh). It counts as busy: git
  refuses merges, rebases, picks, stashes and commits with unmerged files anyway, and so does Thoreau before running git
  ("2 files have conflicts; resolve them and mark them resolved first."). The bar says "2 files have conflicts." ("1
  file has conflicts.") with **Mark Resolved**: unsaved editors asked first ("Mark Resolved Anyway"), the checks of
  Continue (ADR 0042: markers left refuse it, "Conflict markers are left in: …"; files that cannot be checked are
  confirmed first, "Mark them resolved as they are on disk?", Mark Resolved or Cancel), then exactly the unmerged paths
  are staged (`git update-index --add --remove --stdin`), nothing else: "Marked 2 files resolved.". The Commit tab says
  "Files have conflicts; mark them resolved in the log first."; a mixed reset warns "2 files have conflicts; resetting
  keeps them as they are on disk, no longer marked as conflicts.", a hard one "…; resetting replaces them with the
  commit's version.". This covers the squash with conflicts of ADR 0105, an autostash git could not apply (ADRs 0105,
  0106) and a conflicting pop or apply alike.

## Consequences
- Local changes are put aside and brought back without the terminal, also only some files from the Commit tab; a stash
  that conflicts is resolved in the editor and marked resolved from the bar, then dropped from the pane.
- A pop or apply does not restore what was staged (no `--index`): staged changes in a stash come back unstaged. After
  conflicts git leaves the cleanly merged files staged, and Mark Resolved stages the rest, so then everything the
  stash brought is staged.
- An untracked file in the way of a pop leaves the stash applied in part; the header says which file, the stash stays
  to be applied again once it is moved (after the tracked changes are undone, which git would refuse otherwise).
- A stash name that moved since the log was read is refused rather than acting on another stash; the user refreshes and
  chooses again.
- A non-ASCII stash message cannot be given in a POSIX locale (git offers no other way); the dialog's push fails with
  the message why, and stashing without a message works.
- A `git stash drop` in a terminal shows at once: `GitStatusService` watches `.git/refs` itself as well as `.git`
  (amends ADR 0062 and 0013; added 2026-10-29), without subfolders, so on Linux a `git branch x` in a terminal
  (`refs/heads/x`) still counts only with the next change noticed in either; macOS's polling watch reports `refs/heads`
  when its time changes, so there it counts at once. Dropping any stash rewrites `refs/stash` (a new inode, so the
  state stamp differs); one more folder watched costs one more watch key per window.
- Unmerged paths now always show in the bar and keep merges, rebases, picks, pulls and edits of commit messages from
  starting, also those a terminal left; Mark Resolved or a reset is the way out.
- Not offered: `--keep-index`, `--index`, `git stash branch`, showing a stash or its diff, clearing all stashes, partial
  stashes by hunk (`--patch`), stashing staged changes only (`--staged`).
