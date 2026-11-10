# 0158. Hide generated folders in the explorer

- Status: accepted
- Date: 2026-11-10

## Context
Build output, dependency folders and caches (`node_modules`, `build`, `.venv`) fill the tree and are rarely opened.
ADR 0150 already marks library folders; [the generated-folder rules](../features/generated-folders.md) tell which
folders are generated. Two simple rules fail: `.gitignore` alone also hides `.env` files and tmp folders the user
works in, and a list of names alone hides a hand-written `build` folder.

## Decision
- The option `explorer.hideGeneratedFolders` (default on, shared by all windows, stored like `compactFolders`) leaves
  generated folders out of the tree.
- Classification: the structural rules (`generatedReason`, markers and a build-tool catalogue) plus git. In a repository
  a folder is generated only if git ignores it; outside one, or before the window's git status arrived (or for a folder
  it does not cover), the rules alone decide. A tracked folder therefore reappears when the status arrives.
- Every folder listed in the tree classifies its child folders; a top-level node is never classified. Nothing below a
  generated folder is classified (no I/O there; inherited down the tree).
- Re-evaluation: at listing time (first load, watch events, F5, setting change). Each loaded folder remembers its
  structural candidates, so `gitIgnoresChanged()` re-checks only them and lists a folder again only if one flips.
- Reveal exceptions: revealing a path inside a hidden folder, or selecting a new or moved entry in it (create, rename,
  paste, move), adds the folder to a per-window set of exceptions; it
  is shown (with its reason) and survives re-listings. The set is cleared by the setting, Refresh and Collapse All.
- Compacting (ADR 0096) ignores hiding; a compacted row is classified by its first folder.
- Look and toggle: a shown generated folder gets a muted `folder-cog` icon and a tooltip with its reason; a library
  folder keeps the lock (library wins) and lists both texts. The explorer bar has a Hide Generated Folders toggle
  bound to the setting like Compact Middle Folders.
- Rejected: `.gitignore` alone (hides `.env` and tmp folders), a name list alone (hides a hand-written `build`).

## Consequences
An ignored folder that no rule knows stays visible. Before the git status arrives a tracked generated folder is hidden
and reappears with the status. The explorer view gets `ignoredByGit` and `gitIgnoresChanged()`, wired to the window's
git status elsewhere. See ADR 0013 (the explorer), 0096, 0150.
