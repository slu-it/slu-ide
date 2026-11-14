# 0171. Branch menus show only what applies, in sections, with icons; Copy Name is Shortcut+C

- Status: accepted; amends [0040](0040-git-log-branches-pane.md) and [0041](0041-git-write-operations.md); amended by
  [0173](0173-pushing-tags-and-tag-submenus.md) (Push Tag)
- Date: 2026-11-13

## Context
The branches pane's context menu and the submenus of the commit table ([ADR 0041](0041-git-write-operations.md)) listed
every item for every selection and greyed out most of them: a tag showed Rename, Rebase and Pull, a remote node showed
all the branch items. Filter Log by Branch and Copy Name came first although double-click and Enter already filter, and
the items had no icons although the header buttons for Review, Rebase, Pull and Fetch have them.

## Decision
- **Hide what never applies, grey out what is blocked.** An entry is left out when the unrestricted
  `branchActions` ([ADR 0041](0041-git-write-operations.md)) gives nothing for the selection (Rename on a tag, Checkout,
  Rebase and Merge on the current branch, Pull on a local branch, Fetch on a branch, Compare while `HEAD` has no commit). An entry that
  applies but is blocked by a running write or a pending merge, rebase or pick (`menuActions` with `writing` or
  `busy`) stays, disabled, so the menu keeps its shape while git works. The pure model is `branchMenuEntries`
  (`BranchMenuEntries.kt`), used by both menus; no group, mixed selection or empty result opens a popup (JavaFX opens no menu without items; the tree also names the menu afresh on every context-menu request).
- **Order**, in sections with a separator only between non-empty ones: New Branch from '<ref>'…, Review Branch;
  Checkout, Rename…, Delete; Rebase …, Merge …, Pull …; Compare with Current, Show Diff with Working Tree. Fetch is a
  section of its own for a remote or a folder of a remote only (where it is the only item); no branch has it, also not a
  remote branch. Several branches or tags show Delete alone. The stash menu ([ADR 0107](0107-git-log-stashes.md)) is
  unchanged.
- **Copy Name becomes Shortcut+C** in the branch tree (short names of the selected branches and tags and the names of
  selected remotes, one per line); **Filter Log by Branch** goes: double-click and Enter filter. Neither is in a menu
  any more.
- **One icon per action** in `GitLogIcons`, shared by the header buttons and the menu items (Review, Fetch, Rebase,
  Pull, Push from the header; New Branch, Checkout, Merge, Compare, Rename, Delete, Show Diff with Working Tree new).

## Consequences
- A menu is as long as the selection allows; tests look items up by `BranchMenuEntry` or id and check absence
  where an entry is left out.
- The copy and filter commands lose their discoverability in menus; Shortcut+C is listed in
  [keyboard-shortcuts.md](../features/keyboard-shortcuts.md).
- A new branch action is one `BranchMenuEntry` (id, icon, section) plus its cases in `applies`, `entryText` and
  `runEntry`; both menus follow.
