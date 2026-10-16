# 0040. Git log branches pane: a tree of the refs already read, click navigates, double-click filters

- Status: accepted
- Date: 2026-10-16

## Context
The git log ([ADR 0035](0035-git-log-area.md)) reads its refs with every first page for the ref labels
([ADR 0037](0037-ref-labels-and-commit-details.md)) and can be filtered by branches
([ADR 0039](0039-git-log-filters.md)). IntelliJ shows the branches in a pane left of the commits: `HEAD`, local
branches, remotes with their branches and tags as a tree with a search field; a click on a branch jumps to its commit,
a double-click filters the log by it. The commits are loaded in pages of 500, so a branch's commit may not be loaded
yet, and in a large history it may be very far down.

## Decision
- **Position and default:** a pane left of the commit table and its filter bar, inside the expanded log:
  `[branches | commits | changed files and details]`. Shown at first, in the left 20 % of the log's width; the
  commits and the side pane keep their 70/30 split of the rest. Both dividers can be dragged and keep their shares when
  the window is resized (`SharedSplit`, whose first pane can be hidden and shown again with its share). A toggle at the
  left end of the filter bar hides and shows it. The state is per window and not stored; opening another folder keeps
  it shown or hidden but clears the search and the selection.
- **Tree from the refs already read:** the tree is built from `GitLogPages.refs`, read with every first page, without
  JavaFX controls (`branchNodes` in `BranchTree.kt`): `HEAD (Current Branch)` or `HEAD (detached at <short hash>)`,
  then Local, Remote (one node per remote) and Tags, empty groups left out. Names with `/` become folders, compacted
  where a folder holds only one folder (`team/fix`), folders first, by name ignoring case first. A remote is the first
  segment of a remote branch's short name, so a remote whose name has a `/` is split wrongly; git's refs alone do not
  say where the remote's name ends. Tags are collapsed at first (there can be thousands). New refs rebuild the tree,
  keeping the selection (by refname), collapsed nodes (by path) and the scroll position; equal refs keep it as it is.
- **Search:** a field above the tree filters it by the short names, ignoring case, everything expanded while it has
  text; the collapsed nodes come back when it is cleared. A typed text is applied after 200 ms, not on every
  keystroke, as each rebuild builds the items of every ref (thousands of tags); Down and Enter apply it at once.
- **Click navigates, double-click filters,** IntelliJ's default split, without a setting: selecting exactly one
  branch selects its commit's row and scrolls it into view, the focus staying in the tree; a double-click, Enter or
  "Filter Log by Branch" sets the Branch filter to exactly the selected refs through the filter bar's Branch button
  (`FilterBar.setBranches`), so its text, × and popup show them.
- **Navigation loads pages up to 10,000 commits** (`GitLogPages.loadUntil`): if the commit is not loaded, further pages
  load until it is there, the log is complete or 10,000 rows are loaded. It reuses the runs of the pages: opening a
  folder or a filter change ends it, a newer selection replaces it, and a refresh (Refresh or a change in `.git`)
  lets it go on in the reloaded rows, so a click is not lost to a `.git` change. If the refresh moved the selected
  branch, its new commit is selected instead, but only while the user is still on the branch: the pane is shown and
  the search for the old commit is still going or the table has exactly the old commit selected; a commit chosen in
  the table since is never taken away by a fetch. Only if the tree still has that branch selected is the row
  selected, or the header told why not: hidden by the filters, not in the log, or more than 10,000 commits down.
  The refs do not say whether a tag points at a commit; one of a tree or blob is looked for like any other and is
  never found.

## Consequences
- No extra git call: the pane shows what the log read with its last first page, so a new branch or tag alone is not
  noticed until Refresh or a change in `.git` reloads the log, as for the ref labels.
- Navigating to an old tag in a large history may load up to 20 pages, and stops there; so does a tag of a tree or
  blob.
- Rejected: a `git branch` (or `for-each-ref`) call of its own, which would read the refs twice and could disagree with
  the labels; navigating by filtering the log to the branch, which would hide the other commits and lose the context of
  the selected commit.
- Remote names with a `/` are shown split at their first `/`.
- No tracking information (ahead/behind), favourites, or actions that change branches yet.
