# 0083. Zooming document previews

- Status: accepted
- Date: 2026-10-24

## Context
Only images and diagrams zoomed ([ADR 0033](0033-zooming-images-and-diagrams.md)): the image view and the SVG, Mermaid,
Bicep and workflow graph previews. Every other preview (Markdown, HTML, JSON, XML, YAML, CSV/TSV tables, cookie jars,
notebooks, PEM) showed its page at one size. Users want to zoom documents as in a browser: larger to read or present,
smaller to see more of a long JSON or table at once.

## Decision
- **`WebView.zoom` for documents:** every preview that does not zoom in the page (`Preview.zooms == false`) scales the
  whole page with `WebView.zoom`; the page reflows, as in a browser. The zoom belongs to the web view, so it needs no
  page script (HTML pages with their scripts off zoom too) and outlasts every page loaded. Images and diagrams keep the
  in-page zoom of ADR 0033, which can fit; `ZoomControls` drives either through a `ZoomTarget` (`PageZoom`,
  `DocumentZoom`).
- **No Fit:** a document has no natural size to fit; there is no Fit button, and Shortcut+9 does nothing (the key is
  not consumed). Documents open at 100%.
- **Controls on the right**, before the Source / Split / Preview toggles and after the preview's own action buttons,
  shown only while the preview shows: the left of the toolbar already holds each preview's own buttons (Scripts, table
  of contents and Export, Collapse / Expand All and Copy Formatted with its message), which stay where they are.
- **Same input as images:** zoom out, the percentage, zoom in and 100% in the toolbar; Shortcut + `+`/`-`/`0`,
  Shortcut+wheel and pinch only on the web view and the zoom controls, never in the source beside it. Wheel and pinch
  zoom without keeping the point under the pointer in place: `WebView.zoom` has no anchor, and the page reflows anyway.
- **Same steps and range:** the presets of `ZoomSteps`, 10%–1600%.
- **Per view:** the zoom is kept when the page renders again (save, change on disk, theme change), not stored across
  sessions.
- **Scroll sync:** side by side, every preview that scrolls with the source (Markdown, JSON, XML, YAML, CSV/TSV,
  cookie jars) aligns with the source's top line again after a zoom, since the reflow moves the lines.

## Consequences
- Every preview zooms; only images and diagrams fit. One control class serves both, so the toolbar looks the same.
- Amends [ADR 0033](0033-zooming-images-and-diagrams.md), whose Markdown and notebook previews did not zoom.
- Added to [../index.md](../index.md).
