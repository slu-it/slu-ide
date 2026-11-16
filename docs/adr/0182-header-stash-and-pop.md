# 0182. Header quick actions: Stash with untracked files, and Pop

- Status: accepted; amends [0181](0181-new-branch-from-origin-default.md) (the header's groups); see
  [0107](0107-git-log-stashes.md)
- Date: 2026-11-16

## Context
Putting local changes aside before switching work, and bringing them back, took the Stash Changes dialog (ADR 0107)
or the Git menu. The header already has quick actions for the common next step (ADRs 0063, 0181).

## Decision
- Two header buttons, a fourth group between the branch group and the remote group: Review Branch | Fetch and Rebase,
  Fetch and New Branch | **Stash, Pop** | Push, Pull, Fetch, Refresh. Both are outlined like the others, with tooltips
  after 250 ms; no menu items and no key bindings (Git → Pop Latest Stash exists already).
- **Stash** ("Stash Changes with Untracked Files", archive-arrow-down icon, `git-log-stash`) starts `StashAllChanges`
  at one click, with no dialog: it reads what git is in the middle of (busy: the reason goes to the header), then
  stashes with `--include-untracked` and git's own message ("WIP on main: …"), after the question about unsaved
  editors. It says "Stashed local changes as stash@{0}." or "No local changes to stash.". A quick action puts
  everything aside, so untracked files go along; a message or a choice of files is what the dialog is for. Stashing is
  reversible, so nothing is asked.
- **Pop** (archive-arrow-up icon, `git-log-pop`) is `GitLogView.popLatestStash()`, the Git menu's item. Its tooltip names
  the number of stashes: "Pop Latest Stash (1 stash)", "(3 stashes)" (`popTooltip`).
- Enabled: Stash with a repository shown, no operation running, the pending bar not busy and local changes in the
  window's git status (`GitStatusSnapshot.hasLocalChanges`, handed in by `GitLogView.workingTreeChanged`); Pop with no
  operation running, the pending bar not busy and at least one stash (`QuickActionsState.stashCount`, read also while
  the area is collapsed; expanded, it comes from the pages' read of the stashes).
- Known limit: a submodule with only new content or a moved commit counts as a local change, but `git stash` then says
  there is nothing to stash; the header says so.
- The header takes its quick actions as `HeaderAction(run, disabled)`, the rules of being off are in `HeaderFlags`.

## Consequences
One click puts all work aside, one click brings the latest stash back. Stash is enabled from the window's status, not
from a read of its own, so it follows the status's delay after a change on disk.
