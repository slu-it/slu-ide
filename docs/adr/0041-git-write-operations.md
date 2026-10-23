# 0041. Git log changes the repository: checkout, new branch, new tag, rename, delete through the git CLI

- Status: accepted; partly superseded by [0062](0062-git-concurrency.md) (how the log is refreshed after an operation)
- Date: 2026-10-16

## Context
The git log ([ADR 0035](0035-git-log-area.md)) only read the repository so far. IntelliJ's log also checks out
commits and branches, creates branches and tags at a commit and renames and deletes branches and tags, from the
commit table and the branches pane ([ADR 0040](0040-git-log-branches-pane.md)). These are the first operations of
Thoreau that change a repository: they run hooks (post-checkout), take git's locks, change files on disk under open
editors, and can be refused by git (local changes that a checkout would overwrite, a branch that is not fully merged).
Thoreau's reads run with no optional locks and drop git's standard error ([ADR 0013](0013-git-status.md)); the JVM in a
POSIX locale cannot pass non-ASCII arguments ([ADR 0038](0038-changed-files-and-commit-diffs.md)).

## Decision
- **The git command line, as for reads:** a new public `GitBranches` (`modules/git`, a singleton bean) has one method
  per operation, each giving a `GitResult(success, message)`; hooks and the user's configuration apply as in a
  terminal. `GitCli.write` shares the process handling with the reads (environment, `GIT_TERMINAL_PROMPT=0`,
  standard input closed after an optional input, the output read while git runs) but:
  - keeps git's standard error: the message is git's own, trimmed: its standard error, or its standard output if that
    is empty; never parsed or reworded, only its `hint:` lines are left out (they point at command lines such as "run
    'git branch -D …'" or "git config set advice…"), unless nothing else is left;
  - takes optional locks like git itself (no `--no-optional-locks`, no `GIT_OPTIONAL_LOCKS=0`): the `.git` watch is
    meant to notice what a write changes;
  - never opens an editor: `GIT_EDITOR=false`, so where git would (e.g. a lightweight tag with `tag.gpgSign=true`
    needs a message) it fails at once with its message instead of waiting for an editor nobody sees;
  - may take 10 minutes for a checkout (it changes files; filters such as LFS may download them), 60 s for anything
    else, which only changes refs (hooks may run); "git did not finish within 10 min." / "… 60 s." otherwise, and git
    and what it started get SIGTERM, on which git removes its lock files, and are killed after 2 s if still running.
    A command that changes files (a checkout, cherry-pick, revert, their continue, abort and skip, a hard reset:
    `writesFiles`) may have changed some and not others by then, as git writes them one by one: its message
    adds "Files and the index may be partly changed; check git status in a terminal.". Reads are killed at once (they
    hold no locks);
  - once git has ended, reads its output for 2 s more, whatever is left of the timeout: a process a hook left behind
    may keep the pipes open. A write keeps what was read by then; a read (of any git command) whose output is not read
    to its end by then fails rather than give a part.
- **Commands** (names are short names, starts and revisions full hashes or refnames):
  - checkout of a local branch: `git switch --no-overwrite-ignore --end-of-options <name>`;
  - checkout of a commit or tag: `git switch --detach --no-overwrite-ignore --end-of-options <hash or refs/tags/x>`;
  - checkout of a remote branch: its local branch if there is one and it contains the remote branch (as above); if
    it does not, after the confirmation below `git branch -f --end-of-options <b> refs/remotes/<r>/<b>`, then
    `git switch --no-overwrite-ignore --end-of-options <b>`; else
    `git branch --track --end-of-options <b> refs/remotes/<r>/<b>`, then
    `git switch --no-overwrite-ignore --end-of-options <b>`;
  - new branch: `git branch [-f] --end-of-options <name> <start>`, with checkout then
    `git switch --no-overwrite-ignore --end-of-options <name>`;
  - new tag: `git tag --end-of-options <name> <commit>`; with a message
    `git tag -a --cleanup=whitespace -F - --end-of-options <name> <commit>`;
  - rename: `git branch -m --end-of-options <old> <new>`;
  - delete: `git branch -d --end-of-options <names…>` (`-D` once confirmed), `git tag -d --end-of-options <names…>`.
  - `--end-of-options` (git 2.24 or later; checked with git 2.53) keeps a name from being read as an option. Older git
    fails with its own message.
  - `--no-overwrite-ignore`: by default a checkout overwrites ignored files (a local `.env`) at paths the other commit
    tracks without a word, while it refuses for untracked ones. With it, git refuses for both ("The following untracked
    working tree files would be overwritten by checkout: .env … Aborting", checked with git 2.53), shown in the error
    dialog; nothing changed then.
  - **A new branch is created first and checked out second,** never with `git switch -c`/`-C` or `--track`: these
    change the working tree and index before they create the branch, and when creating it fails they leave the other
    commit's files staged on the old branch, where the next commit would take them (git 2.53: `--track` from a
    remote-tracking ref whose remote is not configured, "cannot set up tracking information"; `-c a/b` while a branch
    `a` exists, "cannot lock ref"). `git branch` fails without touching the working tree.
  - **Undo, never at the cost of an existing branch:** where the branch points is read first (`git for-each-ref
    refs/heads/<name>`, which tells a missing branch from a failing git); if that cannot be read, nothing is done
    ("The commit of branch 'x' could not be read."). If git fails after writing the branch (`git branch` with tracking
    that cannot be set up, e.g. remotes whose fetch refspecs map to the same remote-tracking refs, writes the branch
    and then fails; the checkout after it may be refused), a branch that did not exist before is deleted again
    (`git branch -D`) and an overwritten one moved back to its old commit; an existing branch is never deleted. Moving
    it back restores its commit, not an upstream that `branch.autoSetupMerge` changed. If the undo fails as well, its
    failure follows git's message ("The new branch x could not be deleted again: …", "Branch x could not be moved back
    to 1a2b3c4d: …"); if whether git wrote the branch cannot be read, the message says so ("Whether branch x was
    created could not be read."). `-f` is passed only for a branch that exists: on a case-insensitive file system
    (macOS by default) `git branch -f Feature` would write through to `feature`, which an undo would then delete.
  - **A checkout that git reports as failed may have happened:** a failing post-checkout hook makes `git switch` exit
    with an error after `HEAD` moved. After a failed switch to a branch `HEAD` is read (`git symbolic-ref`); if it is on
    the branch, nothing is undone, and the header and the error dialog say "Checked out x, but git reported an error."
    with git's message. After a failed detached checkout of a commit or tag, the same if `HEAD` is detached at its
    commit ("Checked out 1a2b3c4d, but git reported an error.").
  - A remote branch's local name is the part after the remote (`origin/fix` → `fix`), as git's `--track` names it and
    as the branches pane splits remotes. If a local branch of that name exists and contains the remote branch (the same
    commit or ahead), it is checked out as it is, whatever it tracks. Otherwise a confirmation asks first, "Overwrite
    Branch", with "Local branch 'fix' is behind origin/fix. Overwrite it with origin/fix and check it out?" or, if the
    branches diverged or their ancestry cannot be read, "'fix' has commits that are not on origin/fix; they will only
    be reachable through the reflog. Overwrite it with origin/fix and check it out?"; Overwrite or Cancel (the
    default). On Overwrite the branch is moved there with `git branch -f` and checked out (as for a new branch with
    overwrite, including its undo), and the header says "Overwrote fix with origin/fix and checked it out.". With
    git's default `branch.autoSetupMerge`, `git branch -f` from the remote-tracking ref also makes the branch track
    `origin/fix` (replacing another upstream); an undo after a failed switch moves the branch back but not its
    upstream. An unreadable tip shows a "Checkout Failed" dialog saying "The commit of branch 'fix' could not be
    read." and changes nothing.
  - A tag message goes to git's standard input as UTF-8 (messages often have non-ASCII characters, which an argument
    could not carry in a POSIX locale); `--cleanup=whitespace` keeps lines starting with `#`, which git's default
    `strip` would drop, and only removes leading and trailing blank lines and trailing whitespace and collapses
    consecutive blank lines. A blank message makes a lightweight tag.
- **Names git cannot get unchanged are refused without running git:** an empty name, one starting with `-`, and one the
  JVM's `sun.jnu.encoding` charset cannot encode (it would arrive with `?`), with a message saying so. The dialogs check
  names while typing with a pure Kotlin version of `git check-ref-format` (`GitRefNames`; branches also not `-…` and not
  `HEAD`), the refs the log read last ("Branch 'x' already exists", "Tag 'x' already exists"; the old name itself
  disables Rename) and the same charset ("This name cannot be passed to git in this locale.").
- **One at a time, on a thread of its own:** the window's `GitLogView` runs every operation on `sluide-git-write` (not
  the log's reader thread), handled through `dispatch`. While one runs every write action is disabled (menus, F2,
  Delete) and the header says what it does ("Checking out main…"); then what it did ("Checked out main.", "Created
  branch x.", "Created and checked out branch x.", "Deleted 3 refs."), until the next operation or another folder. After
  every operation, also a failed one (which may have done a part), the log is refreshed: new and renamed branches alone
  are not noticed by the `.git` watch ([ADR 0035](0035-git-log-area.md)). The window's title and the explorer's markers
  follow a checkout through the watch (`HEAD`, `index`). Closing the window does not interrupt a running operation:
  stopped halfway, a checkout would leave a half-switched working tree. Git finishes on its own and the outcome is
  dropped.
- **Failures** are shown in an error dialog titled after the operation ("Checkout Failed", "New Branch Failed", "New
  Tag Failed", "Rename Failed", "Delete Failed") with git's message in a selectable, read-only monospace area sized to
  it, long lines wrapped, beyond 12 rows scrolled. There is no smart checkout and no automatic stash: git's refusal is
  the answer, as on the command line.
- **Confirmation for deletes** (and for an overwrite that would orphan commits, below): checkouts run at once, except that of a remote
  branch whose local branch is not at or ahead of it (it asks whether to overwrite the local branch); new branches,
  tags and renames are confirmed by their dialogs. Deleting always asks ("Delete branch 'x'?", "Delete 2
  branches and 1 tag?", listing them as "branch x" and "tag v1" in an area that scrolls, Cancel the default button).
  Branches that `git branch -d` leaves (it goes on after refusing one) are offered for force deletion in a second
  confirmation with git's message and "Force Delete" (`-D` for those only). Which branches are left is read from the
  refs afterwards, not from git's message, which may be translated.
- **Unsaved editors before a checkout** (also a new branch that is checked out): the log asks its `beforeCheckout`,
  which `MainWindow` sets to `EditorArea.saveBeforeCheckout(root)` (generalized for every operation that changes files
  in [ADR 0042](0042-git-log-commit-operations.md): `beforeFileChange(action)`, `saveBeforeGitChange`). Only files in
  the repository count. "These files have unsaved changes. Save them before checking out?" with Save, Check Out Anyway
  and Cancel. Check Out Anyway keeps the changes: an editor whose file the checkout changes keeps them and offers Reload
  or Keep mine ([ADR 0020](0020-editing-and-saving-files.md)), so nothing is thrown away without the user choosing it.
- **Checkout of a remote branch** is disabled when its local branch of the same name is the current one: it would
  only check out the current branch.
- **Leaving a detached `HEAD`:** every checkout (a branch, a remote branch, a revision or tag, a new branch with
  Checkout) first counts the commits it would leave on no branch, remote branch or tag (`GitBranches.leftBehind`: 0
  while `HEAD` is on a branch; for a detached `HEAD`, `git rev-list --count HEAD --not <target> --glob=refs/*`, every
  ref excluded as for git's own count, the stash too). Git only warns about them on its standard error after a
  successful switch ("you are leaving 2 commits behind"), which Thoreau would not show. With any, or if that cannot be
  read, a confirmation asks first ("2 commits of the detached HEAD will no longer be on any branch or tag; they stay
  reachable through the reflog for a while." and "To keep them, cancel and create a branch at HEAD first. Check out
  main anyway?", Check Out or Cancel, the default). A new branch that also overwrites one asks about that first.
- **Overwriting:** "Overwrite existing branch" passes `-f`. The checked-out branch is never overwritten: the dialog
  says "Branch 'main' is checked out." whatever Checkout says (git would refuse `branch -f` for it, while `switch -C`
  would reset it). For another existing branch the dialog notes "Moves 'x' from 1a2b3c4d."; if its commit is not an
  ancestor of the start point (`git merge-base --is-ancestor`, on the write thread before anything changes), a
  confirmation asks "'x' has commits that are not on <start>; they will only be reachable through the reflog.
  Overwrite?" (Cancel the default). If where that branch points cannot be read, nothing is overwritten ("The commit of
  branch 'x' could not be read.").

## Consequences
- Whatever git does, Thoreau does: hooks, `branch.autoSetupMerge` (a branch started from a remote branch tracks it),
  the user's signing settings for tags, and its messages in the user's language.
- Git 2.24 or later is needed for these operations; reads keep working with older git.
- A branch refused by `-d` for another reason (checked out in another worktree) is offered for force deletion too;
  `-D` then fails with git's message.
- Overwriting a branch can leave commits reachable only through the reflog; the confirmation says so, and the reflog
  is where they can be found again.
- A checkout may keep the header saying "Checking out…" for up to 10 minutes, and no other operation starts meanwhile.
- Deferred: deleting remote branches and anything else that needs the network (fetch, pull, push), cherry-pick,
  revert, reset, merge, rebase, stashes, and pushing tags.

Note 2026-10-19: every operation tells its start and its end (also a failed or thrown one) to the window's git status,
which holds its refreshes meanwhile (also of the explorer's markers for an operation that changes or commits files) and
runs one cycle at the end; the log refreshes on that cycle's count, so once per operation, after the trust check ran
again ([ADR 0062](0062-git-concurrency.md), partly superseding the refresh rule above).
