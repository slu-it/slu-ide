# 0059. SVG export as PNG

- Status: accepted
- Date: 2026-10-18

## Context
SVG files open in a preview ([ADR 0017](0017-webview-previews.md)). That preview shows the SVG as an `<img>` in a
`WebView`, so its scripts never run. Users want PNGs of an SVG at 100 %, 200 % and 300 % of its size, e.g. for icons
and screens with high pixel density. The Markdown preview already has an Export menu for HTML
([ADR 0058](0058-markdown-html-export.md)).

## Decision
- **One Export menu per preview, made of `FileExport`s.** Each export has a `label` (the menu item), a `target`
  beside the file, and `write`, which blocks off the FX thread and returns problems to report. `Preview.exports`
  lists them, and `ExportControls` builds the menu from that list. The Markdown preview has "HTML", and the SVG
  preview has "PNG 100%", "PNG 200%" and "PNG 300%". The SVG menu sits beside the zoom controls. This replaces
  `HtmlExport` from ADR 0058.
- **Files are named `logo.png`, `logo@2x.png` and `logo@3x.png`**, the usual names for scale variants on the web and
  in iOS. Each item writes one file. Like the HTML export, an existing file is replaced only after asking, and the
  file is written to a temporary file first and then moved over the target.
- **The SVG is rasterised in an offscreen `WebView` (`WebViewSvgRasterizer`), as the preview draws it:** the same
  WebKit with its CSS, fonts and `foreignObject` text (e.g. Mermaid's labels), still as an `<img>`.
  - **Its size** is the image's natural size, which is what the preview shows at 100 %. An SVG with only a `viewBox`
    takes its size from it.
  - **Sharp at 200 % and 300 %:** the `<img>` is laid out at the scaled size and then snapshotted 1:1, so the vectors
    stay sharp. A 100 % snapshot is not enlarged.
  - **Background:** transparent. Text uses greyscale smoothing, since subpixel smoothing leaves coloured fringes on a
    transparent background.
  - **The WebView only paints in a showing window** (amended 2026-10-20). A Scene that is never shown gives blank
    snapshots. So for the duration of the export the `WebView` sits in the root `Pane` of the IDE window, in a `Group`
    that is not managed, has opacity 0, is mouse transparent and lies far outside the window; the view is not focus
    traversable. A snapshot draws the view without its parents, so the opacity does not show in it. Not `visible =
    false`: a `WebView` whose tree is invisible drops its paints. Not in the preview's own tab either, which turns
    invisible when another tab is selected.
  - **No window of its own** (amended 2026-10-20): before, the `WebView` sat in a `Popup` at (-20000, -20000). On
    macOS with two displays of different scales (a 1× external display and a 2× Retina panel) that crashed the JVM
    natively at the start of an export, even at 100 %, in the popup's first frame: `MTLSwapChain` wraps Glass's
    drawable as the back buffer, and `MTLRTTexture.nInitRTT` clears it from a Java array of the size Prism expects,
    while the native `replaceRegion` copies the drawable's own size. Most likely (from reading the JavaFX source;
    the sizes are not in the crash report) the popup on no screen got a drawable larger than Prism expected, and the
    copy read past the array. With one display connected it worked. Other render textures (snapshots, WebKit's back
    buffer) are created at the size Java expects. Not yet confirmed on a Mac with two displays.
  - **Drawn in tiles of at most 1024 × 1024 px** (amended 2026-10-20): the `WebView` has the size of one tile, the
    `<img>` keeps its full size and is shifted under it tile by tile, and each tile's snapshot is copied into the
    image. Before, the view, WebKit's back buffer and the snapshot had the full size (6144² device pixels at 300 % of
    a 1024 px SVG on Retina). With tiles, they are at most one tile, so that large images need no large textures.
    The tiles were first meant to fix a native crash in `MTLRTTexture.nInitRTT`; they did not, since its cause was
    most likely the popup's back buffer (see "No window of its own").
  - **Snapshot timing:** WebKit paints after a few pulses. The first complete picture came on the third frame, and
    large images showed only the old 800×600 viewport before that. Snapshots of a tile are therefore taken from the
    third frame after it was shifted into view, until two in a row are equal, and at the twentieth frame at the
    latest, since an animated SVG never settles.
  - **Limits:** 8192 px per side and 2²⁵ pixels (about 33 MP) in total, the image being 128 MiB at that size. There is
    a 30 s timeout. A blank tile never counts as settled, so it takes all 20 frames, about a third of a second; at the
    limit (e.g. 5121 × 6552 px), 42 tiles take about 14 s at most.
  - **The saved file's bytes are drawn,** not its text re-encoded, so an encoding named in the XML declaration holds.
  - **No `javafx.swing` dependency:** the pixels are copied into a `BufferedImage` by hand and written with `ImageIO`.
- **Rejected:**
  - JSVG: a small pure-Java library and the least code, but it has no `foreignObject` and only partial CSS, so the
    PNG could differ from the preview.
  - Apache Batik: a heavy dependency, with the same gaps.
  - Enlarging a 100 % snapshot: blurry.
  - An undecorated `Stage` off screen: it takes focus, appears in the taskbar, and window managers keep it on screen.
  - A `Popup` off screen (the first implementation): crashed with displays of different scales on macOS, see above.

## Consequences
- Exporting needs a showing IDE window whose root is a `Pane` ("No window to draw in" otherwise); the view cannot be
  seen. An export whose window closes fails ("The window closed") instead of writing a blank PNG.
- PDF, or PNGs of Mermaid files, can join as further `FileExport`s.
- Added to [../index.md](../index.md).
