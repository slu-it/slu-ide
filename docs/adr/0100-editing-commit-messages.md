# 0100. Git log edits the message of any commit of the current branch by rewriting the commits after it

- Status: accepted
- Date: 2026-10-26

## Context
Only the message of `HEAD` could be changed, with Amend in the Commit tab ([ADR 0048](0048-commit-tab.md)). IntelliJ
offers "Edit Commit Message…" for any commit of the current branch in its log, and rewrites it in memory. The ways git
offers are not fit for it: an interactive rebase with a `reword` step checks out every commit after it, so it rewrites
files and the index, refuses with local changes (or stashes them), flattens merges unless `--rebase-merges` recreates
them (which can conflict again), runs hooks and may stop halfway; `git commit --amend` only works on `HEAD`; `git
history reword` is not in git 2.53. A message can only change by giving the commit a new hash, and with it every commit
after it, so commits that are already pushed, above all those on the repository's default branch, must not be changed
without the user knowing.

## Decision
- **The backend** (`GitRewords` in `modules/git`, a singleton bean): `check(root, branch, commit)` reads what the dialog
  needs (`GitRewordCheck.Allowed` with the commit's message, the other local branches that contain it, the upstream
  that contains it) or why it is refused (`Refused`); `reword(root, branch, commit, message)` changes it
  (`GitRewordResult.Done` with the new `HEAD`, `Unchanged`, `Failed`). Both run git on the calling thread, the git log's
  write thread, and are not cancellable; each git call has its own timeout (`WRITE_TIMEOUT` for the steps of the
  rewrite).
- **A plumbing rewrite, never touching the working tree or the index** (as IntelliJ's in-memory reword):
  1. the new message is cleaned up as the Commit tab's `--cleanup=whitespace`: `git stripspace` (comments kept), the
     message on standard input as UTF-8; a blank result fails with "The message is empty.", one equal to the old message
     cleaned up the same way is `Unchanged` and writes nothing;
  2. the commits to rewrite: the commit, then `git rev-list --topo-order --reverse --ancestry-path <commit>..HEAD`
     (parents first). Commits off the ancestry path, the other side of a merge, are not rewritten and keep their hash,
     so a merge stays a merge with its other parent as it was;
  3. their raw objects are read in parts below `GitCli.MAX_INPUT`, one `git cat-file --batch` per part (the hashes on
     standard input);
  4. each is copied in bytes, not strings, so non-UTF-8 messages of other commits survive: the headers in order, each
     `parent` replaced by its new hash (unchanged if not rewritten), the `committer` line by the current identity and
     time (`git var GIT_COMMITTER_IDENT`, read once; its failure is shown), `gpgsig` and `gpgsig-sha256` dropped with
     their continuation lines; `tree`, `author`, `encoding`, `mergetag` and any other header kept byte for byte. The
     message stays as its bytes are, except for the reworded commit: the cleaned message in UTF-8, and its `encoding`
     header dropped. Each copy is written with `git hash-object -t commit -w --stdin`, so git's checks of commit objects
     apply;
  5. `git update-ref -m "reword: <new subject line>" --stdin` with `update refs/heads/<branch> <new HEAD> <old HEAD>`
     on standard input (the branch name reaches git as UTF-8 whatever the locale; a subject that cannot be passed as an
     argument gives the reflog message "reword <short hash>"): the branch moves at once, and only if it is still at the
     old `HEAD`; otherwise git's message is shown and the branch stays where it is. The written objects are left
     unreachable for git's garbage collection.
- **Refused** (by `check`, and again by `reword`, which may run later), with these messages:
  - a commit that cannot be given to git (`refusal`), git in the middle of anything or that unreadable (the messages
    of [ADR 0042](0042-git-log-commit-operations.md));
  - "'feature' is no longer the current branch." (also for a detached `HEAD`);
  - "'1a2b3c4d' is not on the current branch 'feature'." (not an ancestor of `HEAD`);
  - "'1a2b3c4d' is already on origin/main; its message can't be changed.": the commit is on a default branch. Those
    are, for each remote, what `refs/remotes/<remote>/HEAD` points at, or without one its `main` and `master`; the
    local `main` and `master`; and every local branch of the same name as the one of those remote ones it tracks (a
    local `develop` tracking `origin/develop` that `origin/HEAD` points at, so its commits not pushed yet count too,
    with "is already on develop"); all of them also when one is the current branch. A branch of another name that
    tracks one is a branch of its own: `fix-123` made from `origin/main` (git's `branch.autoSetupMerge`, Thoreau's New
    Branch) tracks it, and its commits can be reworded. The names `main` and `master` are matched in any
    case, as the git log's protected branches ([ADR 0078](0078-deleting-remote-branches-and-tags.md)). The setting
    `init.defaultBranch` is not read: it names the branch of new repositories, not this one's. Read with `git
    remote`, one `git for-each-ref` of the branches (with their symbolic targets and upstreams) and one `git
    for-each-ref --contains`;
    if any of them fails: "Whether '1a2b3c4d' is on the default branch could not be read.", never a guess;
  - "'1a2b3c4d' has more than 1000 commits after it; change its message in a terminal.": the cap keeps a reword in
    seconds (one `hash-object` per commit).
- **What is kept and what is not:** trees, authors, dates and all other headers are kept, local changes stay as they
  are. Signatures are dropped (they would no longer match). The `commit-msg` and `post-rewrite` hooks are not run, as
  no `git commit` or `git rebase` runs. Other local branches that contain the commit keep the old one; `check` names
  them. A commit already on the current branch's upstream needs a force push afterwards; `check` names the upstream.
  Tags at rewritten commits keep the old history and are not named. Git notes are not carried over to the rewritten
  commits. `ORIG_HEAD` is not set; the change is undone through the branch's reflog (`reword: …`). A kept `mergetag`
  header may then name a commit that is no longer a parent of its merge (its tagged side was rewritten too).
- **The UI** (in `modules/git-log`, a later step): "Edit Commit Message…" in the commit table's context menu next to
  "Reset Current Branch to Here…", for exactly one selected commit, with a current branch, nothing in progress and no
  git write running. When chosen, `check` runs on the write thread; a refusal shows in the header status. Otherwise a
  dialog prefilled with the message names the other branches that keep the old commit and, if pushed, says "Already
  pushed to origin/feature; a force push will be needed afterwards."; OK is disabled while the message is blank or
  unchanged.

## Consequences
- Any commit of the current branch that is not on the default branch can get a new message from the log, also with
  local changes and across merges, without git stopping halfway; a failure leaves the branch where it was.
- The rewrite is Thoreau's own code on git's plumbing: copying the headers of a commit object is tested against real
  repositories (`GitRewordsTest`: merges with one or both sides rewritten, a `mergetag` header, Latin-1 messages,
  signatures, input to `cat-file` in several parts, a branch moved meanwhile).
- Signatures, notes and the `commit-msg`/`post-rewrite` hooks are lost for the rewritten commits; a pushed commit needs
  a force push, and other branches and tags keep the old history.
- Not offered: rewording on another branch than the current one, several commits at once, more than 1000 commits after
  the reworded one.
