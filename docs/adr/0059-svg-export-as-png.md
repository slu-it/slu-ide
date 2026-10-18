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
  - **The WebView only paints in a showing window.** A Scene that is never shown gives blank snapshots. So the
    `WebView` sits in a `Popup` far off screen, owned by the IDE window, for the duration of the export. A popup
    takes no focus, has no taskbar entry and is not moved by window managers.
  - **Snapshot timing:** WebKit paints after a few pulses. The first complete picture came on the third frame, and
    large images showed only the old 800×600 viewport before that. Snapshots are therefore taken from the third frame
    on, until two in a row are equal, and at the twentieth frame at the latest, since an animated SVG never settles.
  - **Limits:** 8192 px per side and 2²⁵ pixels (about 33 MP) in total. Snapshot and copy happen on the FX thread,
    each about 128 MiB at that size. There is a 30 s timeout. A blank picture never counts as settled, so a blank SVG
    takes all 20 frames, about a third of a second.
  - **The saved file's bytes are drawn,** not its text re-encoded, so an encoding named in the XML declaration holds.
  - **No `javafx.swing` dependency:** the pixels are copied into a `BufferedImage` by hand and written with `ImageIO`.
- **Rejected:**
  - JSVG: a small pure-Java library and the least code, but it has no `foreignObject` and only partial CSS, so the
    PNG could differ from the preview.
  - Apache Batik: a heavy dependency, with the same gaps.
  - Enlarging a 100 % snapshot: blurry.
  - An undecorated `Stage` off screen: it takes focus, appears in the taskbar, and window managers keep it on screen.

## Consequences
- Exporting needs a showing IDE window ("No window to draw in" otherwise); the popup cannot be seen. A popup hides
  with its owner, so an export whose window closes fails ("The window closed") instead of writing a blank PNG.
- PDF, or PNGs of Mermaid files, can join as further `FileExport`s.
- Added to [../index.md](../index.md).
