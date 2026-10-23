# 0077. Boxes of the workflow graph open their source

- Status: accepted
- Date: 2026-10-23

## Context
The workflow graph ([ADR 0075](0075-github-actions-workflow-graph.md)) shows steps, jobs and nested reusable workflows
and actions, local and remote ([ADR 0076](0076-cache-of-remote-action-sources.md)). Seeing a box, the next question is
where it is written. The parser's JSON carries 1-based lines for jobs and steps, SnakeYAML gives the lines of a
manifest's steps, and the files read are known while drawing. Mermaid runs with `securityLevel: 'strict'`, so that
labels (names, conditions, expressions from the file) can never become markup or script.

## Decision
- **Locations in the graph model.** `WorkflowGraph.locations` maps a Mermaid node or subgraph id to a
  `SourceLocation` (public, root package of `github-actions`): `Local(path relative to the repository root, line)`,
  also for the workflow itself (`.github/workflows/ci.yml`), or `Remote(line, web)` (only what opening needs: the
  file's page at the commit read). The drawing knows the file of every expansion (the resolver hands it over with
  what it read: `SourceFile`), so steps, job boxes, calls of reusable workflows, composite actions and black boxes
  get the line of the file they are written in;
  a nested expansion's box or title gets the line of its `uses:`. Triggers have no lines and stay plain. The locations
  are collected from the drawn diagram, so expansions taken back for the limits leave none behind; they are cached
  with the graph.
- **Remote files link to the web at the commit read.** `RemoteText.Found` has an optional `web` (default null, so
  existing readers compile): the file's page at that commit, `https://<host>/<owner>/<repo>/blob/<commit>/<path>`,
  filled by `WindowWorkflowSources` from the host the file was read from: https also when fetched over ssh, the
  host without an ssh port, an https port kept (GHES). A click opens it with `#L<line>` in the browser. A remote file
  without `web` has no locations, so its boxes are not clickable.
- **A DOM listener, generic in the page.** `MermaidPreview.render` takes the clickable ids and writes them as
  `data-node-links` on `pre.mermaid`. After Mermaid drew, `preview.js` marks the matching elements (class `linked`,
  `data-node-link`): Mermaid 11 prefixes element ids with the drawing's id (`<svg id>-flowchart-<id>-<n>` for nodes,
  `<svg id>-<id>` for subgraphs; a `data-id` is used where it sets one); of a subgraph only its title is linked, so
  a click on its area still zooms. A click on a linked element calls `sluideBridge.openNode(id)` and does not zoom;
  every other click behaves as before. The page knows nothing about workflows.
- **`Preview.openNode(id, host)`** (default: nothing; FX thread) receives it. `WorkflowPreview` looks the id up in
  the graph shown for the latest text: a local file opens through `FileViewHost.openFile(file, line)`, resolved
  against the repository root and normalised, and only if it stays inside; a remote one through `openWebPage`,
  only an https page, with the fragment `L<line>` set by the `URI` constructor (replacing one the page had) and
  passed as ASCII; anything else is ignored and logged.
- **`FileViewHost.openFile(file, line)`** (0-based, like `showChanges`; default: `openFile(file)`) and
  **`FileView.showLine(line)`** (default: nothing) are new. The editor area opens the file as usual and lets its view
  go to the line: code views use Go to Line's movement (`GoToLine.goTo`: caret at the line's start, scrolled into
  view, focused), previews do it in their source, show the source beside a preview shown alone and let the source
  take the focus in place of a pending request for the preview (a zooming preview opened anew asks for its diagram).

Rejected:
- **Mermaid `click` callbacks:** they need `securityLevel: 'loose'`, which lets labels hold markup and script; labels
  come from the files shown.
- **Opening remote files inside the IDE** from the cache: needs a read-only tab for a file that is not on disk;
  maybe later.

## Consequences
- Most boxes of a graph lead to their line; the hover underlines a linked label and the pointer is a hand there,
  a magnifier elsewhere.
- A click on a linked box no longer zooms; zooming by click works on the rest of the diagram.
- The id matching depends on how Mermaid 11 names its elements; the UI test `WorkflowPreviewUiTest` notices a change.
- Remote links need the host's web pages at the same address as its git (true for github.com and GHES); a host
  serving its web elsewhere gets wrong links.
- Listed in [../index.md](../index.md); amends [ADR 0075](0075-github-actions-workflow-graph.md).
