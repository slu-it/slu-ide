# 0033. Zooming images and diagrams

- Status: accepted
- Date: 2026-10-15

## Context
Images, SVG files, Mermaid files and Bicep resource graphs ([ADR 0017](0017-webview-previews.md)) could only be shown
fitted or at their actual size, switched by a click. Large diagrams need more steps in between, small icons need to
be enlarged, and the zoom should be reachable from the toolbar, the keyboard, the wheel and a trackpad, as in image
viewers and browsers. The content is a page in a `WebView`, and Mermaid draws it asynchronously, again after every
theme change.

## Decision
- **Scaling in the page:** `preview.js` gives the zoomed element (the `<img>` of `ImagePage`, the `<svg>` Mermaid draws
  on a `MermaidPreview` page; body class `zoomable`) an explicit size in px: its natural size times the scale. Not
  `WebView.zoom`, which scales the whole page (margins, the diagram note, the error box) and keeps fitting in the
  page's hands, so "fit" and a fixed scale could not be told apart. The page centres the element with auto margins
  (a flex body for images), so that a large one scrolls to every edge; a Mermaid file's `pre` does not scroll itself.
- **The page reports:** after every change the page calls `sluideBridge.zoomed(scale, fitted)`. Several changes start
  in the page (a click, a window resize while fitted, a diagram drawn again), so the JavaFX toolbar (`ZoomControls`)
  follows the page instead of computing the scale itself.
- **Fit never enlarges:** fitted is `min(1, room / size)` per axis. Small images and diagrams show at 100%, as before.
- **The zoom is kept** (fitted or the fixed scale) when the page is rendered again: on a save, a change on disk, a
  theme change. The view holds it and applies it to every page it loads; a diagram applies it after drawing.
- **Steps:** zoom in and out go to the next of fixed presets (10% … 1600%) above or below the scale in effect; wheel
  and pinch scale continuously around the pointer. Zooming stays within 10%–1600%; only fit goes below 10%, for huge
  content, and zooming out from there keeps that scale rather than enlarging to 10%.
- **Shortcuts only on the WebView:** Shortcut + `+`/`-`/`0`/`9`, Shortcut+wheel and pinch are event filters on the web
  view, so they never reach the source editor beside it (Split), whose keys stay its own. The keys also work on the
  zoom controls; neither their buttons nor the mode toggles take the focus, so a click on them leaves it in the web
  view. Like the terminal, an image view and a showing zoomable preview request the focus whenever they get a scene
  (their tab shown), so the keys work without a click first; the percentage label has the fixed width of its widest text.
  Amended by [ADR 0050](0050-focus-when-a-file-tab-is-shown.md): the editor area gives the focus when a file tab is
  shown, by one rule for all views; views no longer take it when they get a scene.

## Consequences
- Zooming works the same in the image view and in the SVG, Mermaid and Bicep previews; Markdown and notebook previews
  do not zoom (their diagrams and images are part of a text).
- The page's script owns the layout of zoomable pages: CSS alone no longer fits the image, so a page without our script
  would show it at its natural size.
- Natural sizes come from the image (`naturalWidth`; an SVG without a size of its own as laid out, 300 × 150) or from
  the `max-width` and view box Mermaid sets; a Mermaid version that changes those needs `preview.js` adjusted.
