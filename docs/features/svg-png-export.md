# SVG export as PNG

**Module:** `previews`: `SvgPngExport`, `CustomSvgPngExport`, `PngSizeDialog`, `WebViewSvgRasterizer`,
`ExportControls` (toolbar of the SVG preview).
Decision in [ADR 0059](../adr/0059-svg-export-as-png.md).

- The **Export** menu of an SVG file, after its zoom controls and before the mode toggles, has the items **PNG 100%**, **PNG 200%** and
  **PNG 300%**. Each writes one PNG beside the SVG: `logo.svg` gives `logo.png`, `logo@2x.png` or `logo@3x.png`, also
  for `Logo.SVG`.
- **PNG Custom…** asks for a width and height in pixels, prefilled with the SVG's own size (what PNG 100% gives).
  Changing one field, by typing or with the arrows, changes the other so that the aspect ratio stays; the ratio is
  always locked. A number typed over 8192 is set to 8192 when the field is committed (Enter, an arrow, or leaving
  the field). If the pixel total is over about 33 megapixels, a hint says so and **Export** is disabled. The PNG is
  drawn by WebKit directly at that size (sharp, not a shrunken snapshot) and named after the size: `logo.svg` gives
  `logo-32x32.png`, also for `Logo.SVG` (`Logo-32x32.png`), so that several sizes beside each other do not clash.
  Example: an 800 × 800 `favicon.svg` gives `favicon-32x32.png` for a favicon fallback. The SVG is measured first
  (the button is disabled meanwhile); a file that is no SVG is reported as "Could not export <file>: Not an SVG
  image". Cancel writes and reports nothing. The existing-file question is asked after the size.
- **What is exported:** the saved file. Unsaved edits are not included.
- **Size:** 100 % is the SVG's own size, which the preview shows at 100 % (`width`/`height`, or the `viewBox` if
  those are missing). 200 % and 300 % are drawn at that size, not enlarged, so edges and text stay sharp.
- **Look:** the background stays transparent. The SVG is drawn by the same engine as the preview, with its CSS, fonts
  and HTML labels, and its scripts do not run. It is drawn unseen inside the IDE window (not in a window of its own,
  which crashed the app on macOS with two displays of different scales), in tiles of at most 1024 × 1024 px, so
  that large images do not need large graphics textures.
- **Overwriting:** if the PNG already exists, a dialog asks whether to replace it. Cancel is the default and writes
  nothing.
- **While it runs:** the export runs in the background and the button is disabled meanwhile. A successful export shows
  no dialog; the PNG appears in the explorer.
- **Errors:** a dialog reports why the export failed, e.g. "Not an SVG image", "Timed out" (30 s) or "The window
  closed" (the IDE window closed during the export). "Too large at 300%: W × H px" (for a custom size "Too large:
  W × H px") means over 8192 px per side or about 33 megapixels in all.
