# 0176. Explorer search: a background walk and a filter inside the lazy tree

- Status: accepted; see [0095](0095-explorer-button-bar-and-select-opened-file.md), [0096](0096-compact-middle-folders.md), [0158](0158-hide-generated-folders.md), [0168](0168-review-window.md)
- Date: 2026-11-15

## Context
The review window already filters the names of its files ([0168](0168-review-window.md)), on an in-memory list. The
explorer's tree is lazy: a folder is read only when it is expanded, so most of the project is unknown to the tree. A
search field above the tree has to find names in folders that were never opened, in every top-level node, and must
show only what the tree itself would show.

## Decision
- **A background walk reads the disk** (`searchNames`, breadth-first per root, shallow matches first) on one daemon
  thread, never the FX thread. It lists folders with the tree's own rule (`shownEntries`, i.e. `walkedEntries`: sorting,
  Hide Generated Files, revealed generated folders), never goes into `.git` and does not follow symlinked folders (a link
  matches by its name). An unreadable folder is skipped.
- **The tree filters itself**: `TreeSearchFilter` says which entries show (a match, a folder holding one, an extra, or
  anything under a folder hit); `FileTreeItem` lists through it, so lazy loading, watches and compaction keep working
  (`TreeSearch` applies and restores it).
- **Match rule** (`NameQuery`): case-insensitive; without `/` the name contains the text; with `/` the path relative
  to the root, with a `/` put in front, contains it and the match ends inside the name. A leading `/` therefore means
  "a name starting with" (`/build` finds `build` and `x/build`, not `mybuild`).
- **Minimum 3 characters, debounced 250 ms** (`ExplorerSearch`, a controller without JavaFX; the delay is a view
  property tests shorten). A new text cancels the running walk; a result of an older text is dropped. Under 3
  characters, or empty, the search ends and the tree is restored.
- **Caps**: 100 matches or 200 000 visited entries end the walk; the status line says "First 100 matches, type more to
  narrow", or with fewer matches "N matches in the first 200 000 entries, type more to narrow". The 100 is one constant
  (`ExplorerSearch.LIMIT`) passed to the walk.
- **Folder hits show collapsed with their full content** when expanded (also kept folders, see extras). A compacted
  row is a hit when any folder of its chain is one.
- **Restore on clear**: the folders expanded before the search expand again; the selection stays selected and is
  revealed; the focus does not move.
- **Extras**: entries the explorer reveals, creates, renames, pastes or moves while searching join the results until
  the text changes or the search ends, also across re-runs.
- **Re-runs** (debounced, same text, extras kept): files changing in loaded folders, Refresh, and a change of Compact
  Middle Folders or Hide Generated Files (`searchInvalidated`). A change during a walk of the same text does not cancel
  it: its result is applied, then the search runs once more. A re-run keeps the status count instead of "Searching…".
  The extras last until the results of a new text are applied (or the search ends).
- **Keys act on the typed text**: Down and Enter in the field wait for the results of the text typed so far (the walk
  starts at once if only the debounce is pending) and act on them, unless the text changed again.
- **Highlight**: `PathCell` splits the name into three labels and gives the matching part `explorer-search-hit`
  (`NameQuery.highlight`, also for a path match and compacted rows).
- **Keys**: Shortcut+F in the tree focuses the field; Escape, Down and Enter in the field (see
  [the feature page](../features/file-explorer.md)). Opening other roots drops the search without restoring and clears
  the field.

## Consequences
- Names in unexpanded folders are found without loading the whole tree; the walk costs disk reads, bounded by the caps.
- Accepted limits:
  - a matched folder holding a deeper match stays collapsed (its full content is one click away);
  - a revealed generated folder that git later un-ignores is classified by the tree but not by the walk until Refresh
    or Collapse All;
  - the git callbacks of `GeneratedFolderFilter` run off the FX thread during the walk (the window's `GitMarkers` reads
    an immutable `gitStatus.snapshot` there, which is harmless);
  - changes in folders the search hid (so unwatched) and files changing in git ignores do not re-run the search.
- Listed in [../index.md](../index.md).
