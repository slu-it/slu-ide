# 0050. Focus when a file tab is shown

- Status: accepted
- Date: 2026-10-17

## Context
Where the keyboard focus went when a file was opened or its tab shown again depended on the view. Code views left it
where it was (in the file tree after opening from there), so typing needed a click first. Image views and zooming
previews (SVG, Mermaid, Bicep) took the focus whenever they got a scene
([ADR 0033](0033-zooming-images-and-diagrams.md)), but previews shown alone that do not zoom (notebooks, PEM) did not,
so their keys needed a click. Each view decided on its own, also when it got a scene for another reason, and only views
that knew to listen to their scene did it at all.

## Decision
- **One rule** for the view of a file tab that is shown:
  - views that show the source get the focus in the source: code views and plain text (also `.env` and `.http`),
    previews in Source mode (Bicep by default), and previews that do not zoom in Split (Markdown, HTML, CSV/TSV,
    cookie jars);
  - previews shown alone (Preview mode: SVG, notebooks, PEM) and images get it in their web view, so that their keys
    (zoom, scrolling) work at once;
  - **exception:** zooming previews in Split (Mermaid, Bicep; `Preview.zooms`) focus the diagram, as before, so that
    the zoom keys work at once; typing needs a click in the source. A stopped one focuses its source.
  A new tab starts at the top of its text (caret at 0); a tab shown again keeps caret and scroll position, only the
  focus moves.
- **Central in the editor area, by who shows the tab:**
  - Opening a file (`open(path)`: file tree, git log, links), also one whose tab is open or selected already, and
    choosing a tab (a click on its header, the overflow menu) always focus it: the user asked for that file.
  - A tab selected in place of another (the selected one closed, e.g. its file deleted in the tree or on disk, or
    replaced by a new view after a rename to another type) takes the focus only if the editor area had it (or nobody
    did). Otherwise the next key would edit a file the user did not choose, e.g. a second Delete in the file tree.
  - A tab whose diff is shown focuses the diff. Other tabs (terminals, commit and working-tree diffs) keep their own
    behaviour. `focusSelected()` gives the focus back to the selected tab, e.g. when the git log is collapsed.
- **`focus()` beside `focusText()`:** `FileView.focus()` follows the rule above and waits until the target is in a
  scene: a view is asked before it is shown, and a split pane adds its items to the scene only when it is skinned
  (`FocusWhenShown`: at once if the target has a scene, otherwise on the next pulse after it got one; a newer request
  drops a pending one). Code views, previews and images override it and also drop a pending request on `dispose()`;
  the default (`focusText()` once the node is in a scene) cannot, which is harmless for views without state, as a
  disposed view is not shown again. `focusText()` stays the immediate "give the focus back to the content" used after
  a diff is hidden; it focuses a Mermaid file's source, not its diagram.
- Views no longer take the focus on their own when they get a scene.

## Consequences
- Opening a file from the file explorer (double-click, Enter) or the git log moves the focus into the editor, for every
  kind of file; going back to the tree needs a click or its shortcut.
- Deleting or renaming files in the tree leaves the focus in the tree, even when the selected tab closes or gets a new
  view.
- A deferred focus cannot steal the focus from a diff shown right after the tab (e.g. from a change marker): the
  editor is hidden under the diff, and JavaFX does not focus nodes that are not visible.
- New views get the rule by default; views whose main node is inside a split pane or a preview, or that must not take
  the focus once disposed, override `focus()`.
- Amends the focus part of [ADR 0033](0033-zooming-images-and-diagrams.md). Listed in [../index.md](../index.md).
