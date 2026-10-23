# 0078. Deleting remote branches, and tags on remotes

- Status: accepted
- Date: 2026-10-23

## Context
The git log deletes local branches and tags ([ADR 0041](0041-git-write-operations.md)). Deleting remote branches was
deferred, with everything else that needs the network. Fetch ([ADR 0043](0043-git-log-fetch.md)) and push
([ADR 0049](0049-push.md)) have since established how the log talks to remotes: git never asks for anything, a run is
cancellable and limited to 10 minutes, and the porcelain report is read rather than translated messages. Users want to
delete `origin/fix` on its remote from the branches pane. When they delete a tag, they also want to delete it on a
remote. Deleting on a remote cannot be undone from Thoreau, and the remote may have moved since the last fetch.

## Decision
- **Scope:**
  - Delete in the branches pane, the Delete key (and Shortcut+Backspace) and Delete in the log's branch submenus (all
    share `BranchMenu`'s `deleteWith`) work on a selection of only remote branches, several and across remotes.
    `<remote>/HEAD`, remotes, folders and a remote branch whose remote is not a configured one don't count. A selection
    that mixes remote branches with local branches or tags deletes nothing.
  - **Protected branches are never deleted,** by the rule Force Push uses ([ADR 0049](0049-push.md),
    `isProtectedBranch`: `main` and `master`, in any case). On the FX thread the mapping is not known, so the name after
    the remote in the short name counts (`main` of `origin/main`): a selection holding a protected branch disables
    Delete, as a mixed one does. On the write thread the operation checks again, by that name before anything runs and
    by the branch the fetch refspecs map (`remoteBranch` without `refs/heads/`) before git pushes. One that gets through
    refuses the whole deletion without running the push: "Deleting origin/main is not allowed (protected branch)." in
    the error dialog, one line per protected branch.
  - Deleting `origin/fix` leaves the local `fix` alone.
  - Tags get no submenu of their own. Instead, when the delete confirmation holds tags and the repository has
    remotes, it adds one unchecked checkbox per remote: "Also delete the tag on origin" ("the tags" for several).
- **Commands:**
  - `GitPush.deleteRemoteRefs(root, remote, refs, cancellation)` runs one push per remote:
    `git push --porcelain --recurse-submodules=no [--force-with-lease=<ref>:<hash>…] --end-of-options <remote> :<ref>…`.
    It uses the environment and cancellation of a push and one timeout of 10 minutes, which also covers the
    `ls-remote` below: that one gets the time the push left. Names that cannot be passed
    ([ADR 0041](0041-git-write-operations.md)), refs not below `refs/` and a lease value that is no full hash fail
    without running git.
  - A **remote branch** is deleted only with a lease on the commit of its remote-tracking ref, so a fetch that runs
    meanwhile cannot weaken it, as for a force push ([ADR 0049](0049-push.md)). A **tag** has no lease.
  - **The branch name on the remote** comes from reverse-mapping `remote.<r>.fetch`: `GitPush.remoteBranch` swaps the
    refspec's source and destination and applies the matching that `trackingRef` uses. The first refspec that maps a
    `refs/heads/…` branch to the remote-tracking ref wins. If no refspec maps it (including a remote without fetch
    refspecs), the branch can't be deleted and git doesn't run. The error dialog says "origin/x: no fetch refspec of
    origin maps a branch to it, so the branch on origin is not known."
  - The operation changes no files: it asks nothing about unsaved editors, holds no git status
    ([ADR 0062](0062-git-concurrency.md)) and is cancellable, as a push is.
- **What git reports** (verified with git 2.53.0 against a bare remote, `LC_ALL=C`):

  | Case | Porcelain line | Exit code |
  |---|---|---|
  | Branch deleted (lease matches) | `-	:refs/heads/fix	[deleted]` | 0 |
  | Branch the remote no longer has (with lease) | `!	(delete):refs/heads/gone	[rejected] (stale info)` | 1 |
  | Branch moved on the remote (stale lease) | `!	(delete):refs/heads/stale	[rejected] (stale info)` | 1 |
  | Tag deleted, no lease | `-	:refs/tags/v1	[deleted]` | 0 |
  | Tag the remote doesn't have, no lease | `-	:refs/tags/v1	[deleted]` (the remote also writes "warning: deleting a non-existent ref") | 0 |
  | Server refusal (`receive.denyDeletes`) | `!	:refs/heads/main	[remote rejected] (deletion prohibited)` | 1 |

  - Refs are deleted one by one, not atomically: a single push can report `[deleted]` for one ref and
    `[rejected] (stale info)` for another.
  - A missing branch and a stale lease print the same line. A missing tag prints the same line as a deleted one.
- **Missing versus stale branches:**
  - Only for refs rejected with `(stale info)` does `git ls-remote --end-of-options <url> <ref>…` ask the remote
    which of them still exist. `<url>` is the URL the push went to, `git remote get-url --push <remote>` (a
    `pushurl` may differ from the URL fetched from), passed only if `refusal` accepts it. It uses the same environment
    and cancellation and the time left of the timeout; only exact names in its output count. With several `pushurl`s
    git pushes to each, but `get-url --push` gives only the first, so `ls-remote` asks only that one: for the other
    URLs, stale versus missing may be wrong.
  - If the ref is listed, the branch changed: "origin/fix changed since your last fetch. Fetch and check it before
    deleting.". If not, it is gone: "origin/fix no longer exists on origin. Fetch to update." (a fetch prunes it).
  - If the URL cannot be read, or `ls-remote` fails, times out or is cancelled, the text names both: "origin/fix
    changed or was deleted since your last fetch. Fetch and check it before deleting.". A cancel then keeps what the
    push already reported as deleted; only the refs still being classified are left open, and the operation counts as
    cancelled only if remotes after it are skipped.
  - This takes an extra round trip only after such a rejection. Nothing reads translated standard error.
- **Tags on remotes:**
  - A tag the remote doesn't have counts as deleted, as git reports it: no `ls-remote`, no note.
  - The tags go to the checked remotes first, in the order of `git remote`, before the existing local deletion.
  - If a remote deletion fails or is cancelled, nothing local is deleted, so the tags stay visible and the user can
    retry. The error dialog says "Tag v1 could not be deleted on upstream; nothing was deleted locally." / "The tags
    could not be deleted on …". It lists the tags that weren't deleted with git's summary, says which tags were
    already deleted on which remote ("Deleted on origin before that: v1, v2."), then gives git's message.
  - Remotes are named joined with "and" ("origin and upstream", "origin, upstream and fork"). The header says, by what
    was actually deleted locally (a branch refused as not fully merged doesn't count): only tags: "Deleted tag v1. Also
    deleted on origin." / "Deleted 2 refs. Also deleted on origin and upstream."; branches too: "Deleted 3 refs, the
    tags also on origin and upstream." / "Deleted 2 refs, tag v1 also on origin."; the local deletion failed after the
    remotes: "Deleted tag v1 on origin." / "Deleted the tags on origin and upstream.".
- **Results** (`DeleteRemoteBranches`, per ref):
  - The header names what was deleted: "Deleted remote branch origin/fix." / "Deleted 3 remote branches.". After it
    come the stale and missing texts, which alone open no dialog.
  - Refusals by the remote (a protected or default branch, a hook), a failure before git reported on a ref (the
    remote cannot be reached) and unmapped tracking refs open "Delete Failed". Its header is "origin/fix could not be
    deleted." / "Not every remote branch could be deleted.". It lists every branch that wasn't deleted with its reason
    (git's summary, e.g. `[remote rejected] (protected)`), then git's message.
  - Cancelled, after what earlier remotes deleted: "Delete cancelled; it may have reached origin. Fetch to check." for
    the remote whose push was stopped; plain "Delete cancelled." when the cancel came between remotes and the ones
    after it were skipped before their push started. The same for tags on remotes.
  - A push stopped by its timeout says "It may have reached origin. Fetch to check." too.
  - The log refreshes as after every operation. A successful push removes the remote-tracking ref itself.
- **Confirmation:**
  - Remote branches: "Delete remote branch 'origin/fix'?" / "Delete 3 remote branches?", listing "remote branch
    origin/fix" in the scrolling area of the existing delete confirmation. Delete or Cancel; Cancel is the default.
  - Tags: the existing confirmation, with the checkboxes below the list (`DeleteConfirmation` holds the alert and the
    checkboxes).

## Consequences
- `main` and `master` cannot be deleted on a remote from Thoreau, as they cannot be force pushed; other important
  branches are guarded by the remote's own protection (protected branches, `receive.denyDeletes`, hooks), whose
  refusals show in the error dialog. A setting for the protected names may come later, for both.
- A branch someone else moved is never deleted blindly. After a stale or missing answer, a fetch shows the truth.
- A tag deleted on a remote can come back with the next fetch from another remote that still has it, or from a
  colleague's push. Thoreau doesn't track that.
- Deleting tags on remotes needs git 2.24 or later, as all writes do; reads keep working with older git.
- Deferred: deleting a remote branch's local branch with it, a tag submenu in the log, pushing tags.
