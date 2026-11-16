# 0181. Header quick action: fetch origin and create a new branch from its default branch

- Status: accepted; see [0063](0063-git-log-rebase.md), [0041](0041-git-write-operations.md) and
  [0043](0043-git-log-fetch.md)
- Date: 2026-11-16

## Context
Starting work usually means: fetch, then branch off `origin`'s default branch. That took Fetch, finding
`origin/main` in the branches pane and its New Branch item. The header already has the same shortcut for rebasing
(ADR 0063).

## Decision
- A header button "Fetch origin and Create new Branch from its Default Branch" (source-branch-plus icon) starts
  `PrepareNewBranchFromDefault`: it fetches `origin` ("Fetching origin…", cancellable), resolves the default branch as
  the quick rebase does (`origin/HEAD`, else `origin/main`, else `origin/master`) and reads the local branches. It
  creates nothing. Only when the fetch is done does `RefActions.finished` open the New Branch dialog, titled "New
  Branch from origin/main": the dialog names the real start point, which is only known after the fetch. Create starts
  a `CreateBranch` as an operation of its own (it asks about unsaved editors when checking out); Cancel leaves the
  header with the fetch note.
- The dialog is the one of the branch menu (name checks, Checkout checked, Overwrite unchecked); it only takes a
  title. Both paths share `RefActions.askNewBranch`.
- The branch is created with `--no-track` (`CreateBranch.noTrack`, `GitBranches.createBranch`): it gets no upstream,
  whatever `branch.autoSetupMerge` says, and the first Push sets one. The branch menu's New Branch is unchanged
  (git's setting decides). Accepted: overwriting an existing branch keeps the upstream configuration it had, git does
  not touch it.
- Texts: a failed fetch "origin could not be fetched; no branch was created."; cancelled "New branch cancelled.";
  no default branch "origin's default branch could not be found (no origin/HEAD, main or master)."; unreadable
  branches "The local branches could not be read."
- Enabled with a remote named `origin`, no running operation and nothing pending. A detached `HEAD` and an unborn
  branch do not disable it (unlike the rebase). `QuickActionsState` (formerly `QuickRebaseState`) reads it also while
  the area is collapsed, like the rebase state, with the same overtaken-answer rules.
- It sits in the header's middle group right after the rebase button: Review Branch | Fetch and Rebase, Fetch and New
  Branch | Push, Pull, Fetch, Refresh; the one-button gap follows it, none follows the rebase button.
- A header button only: no menu item and no key binding.

## Consequences
One click and a name replace a fetch and a hunt through the branches pane. A branch made this way needs its first
Push to get an upstream.
