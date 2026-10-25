# 0096. Compact Middle Folders in the explorer

- Status: accepted
- Date: 2026-10-25

## Context
Source trees are full of folders that hold nothing but one other folder (`src/main/kotlin`, `com/example/app`). In the
explorer each of them was a row of its own, so reaching the code took several expansions and much indentation. IntelliJ
("Compact Middle Packages") and VS Code ("Compact Folders") show such a chain as one row. The explorer's model was one
row per folder: every lookup by path, every watch, the drag sources and targets, rename, delete, cut and copy assumed
it.

## Decision
- **One row for a chain** (`tree/FolderChain.kt`, `FileTreeItem.chain`): a folder row F is shown together with its only
  entry G when F is a real folder (not a link), F's listing holds exactly one entry, G is a real folder (not a link) and
  G is not `.git`; F holding `.git` (a nested repository or submodule, hidden from the tree) has two entries, so it ends
  the chain too. The chain goes on from G the same way; an unreadable folder ends it, and it is at most 64 folders long
  (a safety net). It applies under every top-level node (project, Scratches); a top-level row is never compacted with
  its child.
- **The row** keeps the deepest folder as its value and carries `chain`, all folders from the first one (the entry in
  its parent folder) down to the deepest. Its label is the names joined by `/` (`src/main/kotlin`, `displayName`), its
  tooltip the deepest folder's full path, its icon and markers those of the deepest folder (the middle folders hold
  nothing else).
- **As a target the row is the deepest folder**: expanding it, dropping on it, pasting into it, New File…, folder
  actions, Open in Terminal and Open in Finder all go into `kotlin`. Code that used `item.value` stays right.
- **As a source the row is the whole chain**, its first folder (`sourcePath`): Delete (question, unsaved count,
  `onDeleted`), drag move and copy (`sourceRows`, `canDropRows`: "is a source", "inside a source", "already in the
  target" compare `src`), Cut and Copy (clipboard paths, the `:cut` dimming) and the locks of a running delete. The
  questions name the row as shown ("Delete the folder “src/main/kotlin” and everything in it?", "Move “src/main/kotlin”
  to …", copies "“src/main/kotlin” → “src-copy/main/kotlin”"). Moved, pasted and copied rows are found again by their
  first folder.
- **Rename of a compacted row** (F2) shows `src/main/kotlin` with the last name's base name selected. Any name may
  change, the number of names may not ("Keep the 3 folder names separated by “/”: …"); each is checked like a rename in
  its own folder (`nameProblem`, case-only changes allowed). The changed folders are renamed in place with
  `FileOperations.rename`, the deepest first so the paths of the outer ones stay valid; each rename is reported with
  `onMoved` (tabs and project data follow), the first failure stops the rest and is shown. The row (rebuilt) is selected
  and keeps its expansion. Other rows rename as before.
- **Live updates**: every middle folder of a compacted row is watched (`tree/TreeWatches.kt`, which now holds all
  watches of the tree); a change there reads the row's parent folder again. `FileTreeItem.refresh()` matches children by
  their first folder and replaces a row whose chain changed, expanding a new row only if all of its folders were
  expanded before: an expanded row that splits stays open in all its parts (the deepest folder too), a join is open only
  if every joined folder was. So deleting `x.txt` from an expanded `src` that holds a collapsed `main` gives a collapsed
  `src/main` (it showed `main` collapsed before, the same content). A loaded folder whose own change makes its row stand
  for other folders (it now holds only a folder, or the last file beside one is gone) has its parent read again, too;
  only a folder that now lists a single folder reads its chain for that. Middle folders deleted and recreated (the watch
  of a deleted folder ends) are watched anew when their row's parent or a middle folder above reports it. The selection
  stays on the same entry: a selected row that was replaced is replaced by the row standing for its folder now (a middle
  folder's compacted row). Lookups (`findForReveal`, `expandTo`) step through compacted rows, read a row's parent again
  when its chain is out of date, and find a middle folder as its compacted row.
- **Setting**: Compact Middle Folders is on by default and remembered for all projects and windows as
  `explorer.compactFolders` in `settings.properties` (`ExplorerSettings`, a singleton bean, as
  `runs.history.expanded` of [ADR 0091](0091-argument-history-of-runs.md)). The toggle is the fourth button of the
  explorer bar ([ADR 0095](0095-explorer-button-bar-and-select-opened-file.md)), a `ToggleButton`; toggling rebuilds
  every loaded folder, keeping what was expanded and selected.
- **Rejected:** clickable segments in a compacted row that act on one middle folder (VS Code): the row would need hit
  testing per name and a selection below the row level, for little gain; turn the toggle off for that. Deleting (or
  dragging) only the deepest folder of a row: the row would vanish partly and leave empty middle folders, unlike what
  the row looks like. Watching the deepest folder of every compacted row, and every folder that was never expanded, to
  join chains at once: twice the watches for a rare change.

## Consequences
- A folder that was never expanded does not watch its own content, so if it comes to hold only one folder (or its
  only folder gets a sibling) the change shows when its parent is read again (any change there, F5, toggling).
- Reading ahead costs: every listing of a folder (loading, each refresh after a change, F5, toggling) reads up to two
  entries of each subfolder, and of each folder down its chain, to build the rows, all on the JavaFX thread. In an
  expanded folder with thousands of subfolders that keep changing (a large `node_modules` during `npm install`) or
  on a slow network share, the explorer gets slower per change; turning the toggle off removes the extra reads.
- Deleting, moving or cutting a compacted row acts on the first folder with everything below it, also any files that
  appear in a middle folder while the question is open.
- A row inside a cut folder is not dimmed, as before; a compacted row is dimmed when its first folder is cut.
- Listed in [../index.md](../index.md).
