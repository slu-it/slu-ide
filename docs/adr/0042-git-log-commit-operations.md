# 0042. Git log cherry-picks, reverts and resets through the git CLI, conflicts left for the user

- Status: accepted
- Date: 2026-10-16

## Context
The git log changes branches and tags ([ADR 0041](0041-git-write-operations.md)). IntelliJ's log also cherry-picks and
reverts commits and resets the current branch to a commit from the commit table. Unlike a checkout these can stop
halfway: a cherry-pick or revert with conflicts leaves `CHERRY_PICK_HEAD` (`REVERT_HEAD`), unmerged index entries and
conflict markers in files, and one whose changes are already there stops with nothing to commit. A reset can drop
commits from every branch and, hard, local changes. Git 2.53 behaves as follows (checked in tests):
- `git cherry-pick` of changes already on `HEAD` exits 1 and leaves `CHERRY_PICK_HEAD` with a clean tree;
  `git revert --no-edit` of changes already undone exits 1 without `REVERT_HEAD` but leaves `MERGE_MSG` (which the
  next `git commit` would take as its message) and `AUTO_MERGE`. Git refusing to start (local changes in the way)
  exits 128 and leaves nothing.
- A cherry-pick or revert started during a merge (`git merge --no-commit`) concludes the merge with its commit; one of
  several commits (`git cherry-pick A..B`) keeps its state in `sequencer`, also after the user committed a stopped one
  by hand (then without `CHERRY_PICK_HEAD`), and `git revert --quit` would delete that state.
- Conflicts git cannot mark: binary files keep one side's version, a file deleted on one side and changed on the other
  stays on disk, submodules and symbolic links get no markers. Staging what is on disk would silently drop a change.
- After resolving, `git commit --no-edit` keeps the `# Conflicts:` lines of `MERGE_MSG` in the message;
  `git cherry-pick --continue --no-edit` and `git revert --continue --no-edit` commit the original (revert) message.
- `--exclude=<pattern>` before `--branches` takes the branch name without `refs/heads/`; with the prefix nothing is
  excluded.

## Decision
- **Commands** (`GitCommits` in `modules/git`, a singleton bean; revisions are full hashes after `--end-of-options`;
  10 minutes each, as they change files or the index, like a checkout):
  - cherry-pick `git cherry-pick --end-of-options <hash>`, revert `git revert --no-edit --end-of-options <hash>`;
    a merge commit only after a confirmation (Cancel the default) and then relative to its first parent (`-m 1`);
    ignored files in the way only after a confirmation (below);
  - abort `git cherry-pick --abort` / `git revert --abort`;
  - continue: stage the files that are still unmerged, then `git cherry-pick --continue --no-edit` /
    `git revert --continue --no-edit`;
  - reset `git reset --soft|--mixed|--hard --end-of-options <hash>`.
- **Ignored files in the way of a cherry-pick or revert** are asked about first. Git overwrites them without a word:
  an ignored local `.env` the commit adds, an ignored `build/` folder (with what is in it) where the commit adds a file
  `build`, an ignored file `out` where it adds `out/x.txt` (checked with git 2.53; `git cherry-pick` has no
  `--no-overwrite-ignore` as `git switch` has, ADR 0041). For untracked files that are not ignored it refuses ("The
  following untracked working tree files would be overwritten by merge"), changing nothing.
  - Found before git runs (`GitCommits.pickOverwrites`): `git merge-tree --write-tree --merge-base=<base> <ours>
    <theirs>` gives the tree git's merge would write, with the same merge configuration (renames, directory renames,
    conflicts with their markers; checked with git 2.53 against `git cherry-pick` and `git revert`): a cherry-pick with
    the commit's parent as base (the empty tree, `git hash-object -t tree --stdin`, for a root commit), a revert with
    the commit as base and its parent as the other side, `-m 1` the first parent; `HEAD` as ours, the empty tree on a
    branch without commits. It stores the merged objects, unreferenced, and touches neither index nor files, but merge
    drivers of the configuration run and a partial clone may fetch missing objects, as for the pick itself (both only
    in a trusted folder, [ADR 0053](0053-project-trust.md)). The hard reset's check (`overwrittenFiles`, Reset below)
    then finds what a checkout of that tree would overwrite or delete; the untracked files that are not ignored
    (`git ls-files --others --exclude-standard`) are left out, as git refuses for them. Not checked, as git refuses
    them: a merge commit without its first parent, staged changes (`git diff-index --cached --quiet HEAD`), and
    anything while git is in the middle of something (the pick is refused at once, above).
  - With any, the outcome asks first ("2 ignored files will be overwritten or deleted by the cherry-pick of 1a2b3c4d;
    git keeps no copy of them:", the files in the details area, Cherry-Pick or Revert, Cancel the default). The
    confirmed pick reads them again and runs only if they are among those shown; others by then (a build recreated a
    folder while the dialog was open) are asked about again.
  - If that cannot be read, the question says so ("Whether the cherry-pick of 1a2b3c4d overwrites or deletes ignored
    files could not be read. Cherry-pick 1a2b3c4d anyway?"): with git older than 2.40 (no `--merge-base`) for every
    pick, although writes need only git 2.24 (ADR 0041), and before git 2.44 (merge-tree takes trees) for a root
    commit or a branch without commits.
  - A file in the way of a folder in a conflict is renamed by git (`d~1a2b3c4 (subject)`); `merge-tree` names it
    differently (`d~<full hash>`), so an ignored file at git's name would not be found, which takes an ignored file
    with exactly that name.
- **What git is in the middle of** is read, not parsed from its (translated) messages: a rebase (`rebase-merge`,
  `rebase-apply`), `git am` (`rebase-apply/applying`), a sequence (`sequencer`, its kind from the first line of its
  `todo`), at their `git rev-parse --git-path` (per worktree), a merge (`MERGE_HEAD`), else a single cherry-pick or
  revert (`git rev-parse -q --verify CHERRY_PICK_HEAD^{commit}`, `REVERT_HEAD`) with the unmerged paths
  (`git ls-files -u -z`). A rebase stopped at a conflict also has `CHERRY_PICK_HEAD`; it counts as a rebase.
- **One commit at a time, and only when git is in the middle of nothing:** the actions need exactly one selected
  commit; several (a sequence) are deferred. While anything is in progress, or that cannot be read, nothing is started
  and git is not run ("A merge is in progress; finish or abort it first.", "A cherry-pick sequence is in progress;
  continue or abort it in the terminal.", "A cherry-pick of 1a2b3c4d is in progress; continue or abort it first."), so
  a merge is never concluded by a cherry-pick and a sequence's state is never cleared. The menu items are disabled
  while the bar (below) shows something; Reset stays enabled, the documented way out of a stuck state, but its
  confirmation always says what it does to what is in progress (see Reset below).
- **What a stop is** is read after git failed: whether the pending head is there for the commit, the unmerged paths,
  whether `HEAD` moved and whether the index equals `HEAD` (`git diff-index --cached --quiet`).
  - **Nothing to commit** (`HEAD` where it was, no unmerged paths, index as `HEAD`): cleared at once, with
    `git cherry-pick --skip` when git left its state (git's own advice; unlike `--abort` it never moves `HEAD`); for a
    revert, which leaves no `REVERT_HEAD` (only after exit value 1, so a refusal to start is a failure), by removing
    exactly what it left: `MERGE_MSG` at its `--git-path` and `AUTO_MERGE` (`git update-ref -d`), not with
    `git revert --quit`, which would also delete a sequence's state. Untracked files and local changes stay (the index
    equals `HEAD` then; git refuses to start with staged changes). The header says "Nothing to cherry-pick: the changes
    of 1a2b3c4d are already on main." ("on HEAD" when detached) or "Nothing to revert: the changes of 1a2b3c4d are
    already undone."; no dialog. A Continue that ends with nothing to commit is cleared the same way.
  - **Conflicts** (or a stop without them, e.g. a hook): left for the user. A dialog "Cherry-Pick Stopped" lists the
    conflicted paths, git's message in its details, with Resolve (default: nothing more happens) and Abort Cherry-Pick.
  - Anything else is a failure with git's message, as in ADR 0041.
- **A bar above the table** (below the filter bar) shows what git is in the middle of, also when started in a terminal,
  read with every refresh after the refs on `sluide-git-log` (`GitLog.inProgress`); a refresh whose first page fails
  hides it. A single cherry-pick or revert: "Cherry-pick of 1a2b3c4d is in progress: 2 files have conflicts." (or
  "conflicts resolved." once none is unmerged) with Continue and Abort, which read the state again when they run and
  refuse anything else. Abort asks first ("Abort the cherry-pick of 1a2b3c4d? Its changes are undone, also what was
  done to resolve its conflicts.", Abort Cherry-Pick or Cancel, the default): by then resolutions may have been made.
  Abort Cherry-Pick in the dialog right after the stop does not ask, as nothing is resolved yet. A merge, rebase,
  `git am` or sequence: "A cherry-pick sequence is in progress; continue or abort it in the terminal." without
  buttons: Continue and Abort would act on all of it.
- **Continue checks what it can and stages only the conflicted files.** The unmerged paths are sorted by their index
  stages:
  - **text conflicts** (ours and theirs present, regular files, not binary by git's test, a NUL byte in the first
    8000 bytes of either blob, nor by the `merge` attribute: `-merge`, `merge=binary`, `binary`; read with
    `git check-attr --stdin -z`), present on disk: refused if a line starts with a conflict marker, `<` or `>` as many
    times as the file's `conflict-marker-size` attribute says (7 by default) followed by a space or the line's end
    (`=======` alone is too common): "Conflict markers are left in: a.txt, b/c.txt", nothing done. The files are read
    as bytes, directly when the JVM can name them, after their size on disk was checked: a file above 5 MB is not read
    (e.g. an LFS pointer conflict resolved with the downloaded content) but goes into the confirmation below. A path
    the JVM cannot pass, nor open (non-ASCII in a POSIX locale, [ADR 0038](0038-changed-files-and-commit-diffs.md)), is
    read after `git hash-object -w --no-filters --stdin-paths` stored it, the path on its standard input, with up to
    10 minutes for it; its size is known only then, git offers no way to read it before. The unreferenced objects are
    pruned by git's garbage collection. Not `git grep`, which searches every tracked file and ignores
    `conflict-marker-size`, nor `git diff --check`, which also reports a `=======` line that came from the other side
    (a Markdown heading);
  - **all others** cannot be checked: missing on disk (whatever it was, its deletion will be committed; checked
    first), binary, larger than 5 MB, deleted on one side (the file on disk will be committed), submodules (the commit
    checked out in them), symbolic links. Before anything is done a confirmation (Cancel the default, "Commit As on
    Disk") lists them with the reason ("bin.dat — binary, the version on disk will be committed", "m.txt — deleted on
    one side, the file on disk will be committed", "gone.txt — deleted on disk, the deletion will be committed"). The
    confirmed list goes with the continue: if the files read again are not all among it (e.g. a file was deleted in a
    terminal meanwhile), the confirmation is shown again with the new list; only then are they staged;
  - a submodule that is not checked out (an empty folder, no `.git`) is refused before the confirmation ("Submodule
    sub is not checked out: the commit to keep cannot be taken from disk. Resolve it in the terminal."), and so is a
    folder or special file (a FIFO) in place of a text conflict ("… is not a regular file (a folder or a special
    file), which git cannot stage."): `git update-index` cannot stage them. Neither, nor a symbolic link in place of a
    text conflict (unchecked, "symbolic link"), is followed or read.
  Then exactly the unmerged paths are staged with `git update-index -z --add --remove --stdin`, the paths on its
  standard input: clean filters and `core.autocrlf` apply as for `git add`, a deleted file is staged as deleted. Never
  `git add -u` or `-A`, which would sweep unrelated local edits into the commit.
- **`--continue --no-edit` rather than `git commit --no-edit`:** with git 2.53 the former commits the original message,
  the latter keeps the conflict comments of `MERGE_MSG`, as no editor strips them (`GIT_EDITOR=false`). Older git
  (before about 2.29) may commit with `git commit --no-edit` there and keep those lines too; `--cleanup=strip` is no way
  out, as it would also drop lines of the original message that start with `#`.
- **Reset** ("Reset Current Branch to Here…", enabled when the refs read show a `HEAD` commit): a dialog "Reset Current
  Branch" ("Reset HEAD" when detached), "Reset branch 'main' (at 5e6f7a8b) to 1a2b3c4d", Soft, Mixed (preselected) and
  Hard with IntelliJ's explanations, Hard's in a warning colour. Before resetting, on the write thread, unless already
  confirmed:
  - the commits that would no longer be on any branch, remote branch or tag: `git rev-list --count HEAD --not <target>
    --exclude=<current branch> --branches --tags --remotes` (detached: without `--exclude`, `HEAD` itself is the only
    ref left out); the current branch is read with `git symbolic-ref -q HEAD`, which tells detached (exit 1) from a
    failure;
  - for Hard only, the tracked files with local changes, staged or not (`git status --porcelain -z
    --untracked-files=no --no-renames`);
  - for Hard only, the untracked and ignored files it would overwrite or delete: git does so without a word (checked
    with git 2.53). Read from `git diff-index --cached --raw -z --no-renames <target>`, in proportion to the
    difference: for every file or submodule only the target has (status `D`; `A` and unmerged `U` paths are tracked),
    an untracked or ignored file or link at its path (a local `.env` the target tracks), at a folder of it (git replaces
    that file or link alone, so nothing below it counts, not what a link points to), or for a file the untracked and
    ignored files in a folder at its path, which git deletes with the folder (a folder of tracked files only, or an
    empty one, loses nothing; a nested repository in it, history and all, is one entry `sub/`, as git lists it; a
    submodule's folder is kept). With `core.ignorecase` (`git config --type=bool`) a path is tracked under any case (a
    rename `README.md` to `Readme.md`). Checked by the JVM, not following links, or for paths it cannot name by `git
    ls-files -z --others` (every file not in the index, the ignored ones too);
  - for Mixed only, the files whose staged changes differ from the file on disk (`git diff --cached HEAD` and `git
    diff`, `--name-only -z --no-renames --diff-filter=u --ignore-submodules=dirty`, both listing it, e.g. after `git
    add -p`): their staged version is neither in `HEAD` nor on disk, and the mixed reset replaces the index. Unmerged
    paths are left out (their stages come from commits), and so are submodules with changes inside their checkout only;
  - what git is in the middle of (as far as that can be read): the reset is the way out, but git 2.53 abandons a merge
    or a single cherry-pick or revert with it, and leaves a sequence, rebase or `git am` in progress, which continues
    from where `HEAD` was reset to; a mixed or hard reset drops the changes of the commit (patch) it stopped at, a soft
    one keeps them staged.
  A soft reset git would refuse (during a merge, or with unmerged paths: "Cannot do a soft reset in the middle of a
  merge.") fails at once, without a question: "A soft reset is not possible during a merge or while files have
  conflicts; choose Mixed or Hard." Otherwise, if anything is above 0, in progress or cannot be read, a confirmation
  (Cancel the default, "Reset") says so, a sentence each ("A cherry-pick of 1a2b3c4d is in progress; resetting
  abandons it.", "2 commits will no longer be on any branch or tag; they stay reachable through the reflog for a
  while.", "Local changes in 3 files will be lost.", "Staged changes in 1 file that differ from the file on disk will
  be lost.", "2 untracked or ignored files will be overwritten by the commit's version:" with the files listed in its
  details, "Whether … could not be read."), then the reset runs confirmed. The header says "Reset main to 1a2b3c4d
  (hard); it was at 5e6f7a8b.", which gives the way back.
- **Confirmations are the operation's own:** an outcome may carry a `RefConfirmation` (title, question, optional
  details, button, the operation to run once confirmed, which does not ask about unsaved editors again); the overwrite
  question of ADR 0041 is one too. Without details the dialog has no empty content area.
- **Unsaved editors** are asked about before every operation that changes files, named by its action: the log's
  `beforeFileChange(action)`, which `MainWindow` sets to `EditorArea.saveBeforeGitChange(root, action)`. "These files
  have unsaved changes. Save them before you cherry-pick?" with Save, "Cherry-Pick Anyway" and Cancel. Asked for
  checkouts ("Check Out"), cherry-pick, revert, hard reset, abort and continue (it stages the files from disk, so an
  unsaved resolution matters); not for soft or mixed resets and branch or tag operations. For a merge commit, after
  its confirmation.
- Threading, refresh after every operation, error dialogs and closing during an operation are as in ADR 0041.

## Consequences
- A conflict is resolved in the editor (or any tool), then Continue; the conflicted files stay unmerged in the index
  until then, so the bar keeps counting them.
- Hooks and the user's configuration apply (`commit.gpgSign`, `prepare-commit-msg`); a hook that makes the commit
  fail leaves the state with the bar, as git does.
- A text file the user resolved by keeping a line that starts with a full-length conflict marker cannot be continued
  from Thoreau; a binary or deleted file is committed as it is on disk once confirmed, whatever it holds.
- Merges, rebases and sequences are shown, but continued or aborted in the terminal.
- Limits: no merge tool or three-way view, no `reset --keep`/`--merge`, no several commits at once, no `-x` or edited
  messages. Git 2.24 or later is needed (`--skip` 2.23, `--end-of-options` 2.24).
