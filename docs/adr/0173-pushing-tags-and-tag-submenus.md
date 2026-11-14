# 0173. Pushing tags from the tag menus, and tag submenus in the commit menu

- Status: accepted; amends [0049](0049-push.md) (pushing tags was not done),
  [0078](0078-deleting-remote-branches-and-tags.md) (a tag submenu and pushing tags were deferred),
  [0171](0171-context-sensitive-branch-menus.md) (a new entry) and [0172](0172-commit-menu.md) (tags at the commit)
- Date: 2026-11-14

## Context
The git log creates tags (New Tag…, [ADR 0041](0041-git-write-operations.md)) and deletes them, also on remotes
([ADR 0078](0078-deleting-remote-branches-and-tags.md)), but cannot push them: a tag made in Thoreau reached a remote
only through a terminal. The commit table's menu shows a submenu for each branch at the commit, grouped under
"Branches" when there are several ([ADR 0172](0172-commit-menu.md)); the tags at the commit had none, so their items
(New Branch from, Checkout, Delete, Compare, Show Diff with Working Tree) were only in the branches pane.

## Decision
- **The user's choices (2026-11-13):**
  - **Tags at the commit get submenus, grouped apart from the branches.** One tag: its own submenu, named after it,
    with the tag icon of the ref labels; two or more: one "Tags" menu (`GitLogIcons.tags`) with a submenu each. The
    first section of the commit menu holds the branch group (one branch's submenu or "Branches"), then the tag group:
    "Branches", "v1.0" or "main", "Tags". `HEAD` gets none. A tag's submenu is the branches pane's menu for that tag
    (`branchMenu`, [ADR 0171](0171-context-sensitive-branch-menus.md)), unchanged inside.
  - **Push Tag** ("Push Tags" for several), in the branch menus: the branches pane for a selection of only tags, and
    a tag's submenu in the commit menu. With one remote it pushes at once: pushing a tag loses nothing, so nothing is
    asked. With several, a dialog asks where: "Push tag 'v1'?" / "Push 3 tags?", the tags listed, one checkbox per
    remote in the order of `git remote`, `origin` checked (else the first remote); Push is the default button and is
    disabled while no remote is checked.
  - Rejected for now: "Push All Tags" on the Tags group, and a "Push tags" option in the Push dialog
    ([ADR 0049](0049-push.md)).
- **The entry** (`BranchMenuEntry.PUSH_TAG`, with the Push icon of the header) sits in the section of Rebase, Merge and
  Pull. It is left out without a remote, and for a selection that holds anything but tags; it is greyed out while an
  operation runs, but not while a merge, rebase or pick waits: it changes no files, as Fetch.
- **The command** (`GitPush.pushTags`): `git push --porcelain --recurse-submodules=no --end-of-options <remote>
  refs/tags/<t>:refs/tags/<t>…`, full refnames on both sides, each tag once, never forced, with the environment,
  timeout and cancellation of a push ([ADR 0049](0049-push.md)). Names that cannot be passed to git (`refusal`) and
  names that are no valid tag name (`GitRefNames.isValidTagName`) fail without running git: `v*` would otherwise be a
  pattern that pushes every matching tag.
- **What git reports** (verified with git 2.47.3 against a bare remote, `LC_ALL=C`; a lightweight and an annotated tag
  alike):

  | Case | Porcelain line | Exit code |
  |---|---|---|
  | Tag new on the remote | `*	refs/tags/v1:refs/tags/v1	[new tag]` | 0 |
  | Remote has it at the same object | `=	refs/tags/v1:refs/tags/v1	[up to date]` | 0 |
  | Remote has the name at another object | `!	refs/tags/v1:refs/tags/v1	[rejected] (already exists)` | 1 |
  | A hook of the remote declines | `!	refs/tags/v1:refs/tags/v1	[remote rejected] (pre-receive hook declined)` | 1 |

  - Git sends each tag on its own: the others of a push with a refused tag are still pushed.
- **The operation** (`PushTags`) pushes to the chosen remotes one after the other, cancellable, asking nothing about
  unsaved editors:
  - Header: "Pushed tag v1 to origin." / "Pushed 3 tags to origin and upstream."; when every tag was there already:
    "Tag v1 is already on origin." / "The tags are already on origin and upstream.".
  - A remote that refuses a tag stops the remotes after it. "Push Failed": "Tag v1 could not be pushed to origin." /
    "Not every tag could be pushed to origin.", then a line per tag not pushed ("v1: origin has another tag v1; delete
    it there or rename yours." for an existing tag, "v1: no answer from origin" when git reported nothing on it,
    else git's summary), "Skipped: fork." for the remotes after it, "Pushed to origin before that: v1, v2." for the
    remotes before (only the tags they took), and git's message.
  - Cancelled: "Push cancelled; it may have reached origin. Fetch to check." for the interrupted remote, "Push
    cancelled." between remotes, after what earlier remotes got.

## Consequences
- A tag made in Thoreau can be published without a terminal; Thoreau does not track which remote has which tag, so
  Push Tag is offered whether or not the remote has it, and "is already on" tells afterwards.
- Replacing a tag on a remote stays a two-step, deliberate act: delete it there
  ([ADR 0078](0078-deleting-remote-branches-and-tags.md)), then push; there is no force.
- The commit menu reaches every action of a tag at the commit; with many tags the "Tags" menu keeps the top short.
- Still not done: pushing all tags, pushing tags along with a branch in the Push dialog.
