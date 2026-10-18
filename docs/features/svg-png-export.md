# SVG export as PNG

**Module:** `file-viewers`: `SvgPngExport`, `WebViewSvgRasterizer`, `ExportControls` (toolbar of the SVG preview).
Decision in [ADR 0059](../adr/0059-svg-export-as-png.md).

- The **Export** menu beside the zoom controls of an SVG file has the items **PNG 100%**, **PNG 200%** and
  **PNG 300%**. Each writes one PNG beside the SVG: `logo.svg` gives `logo.png`, `logo@2x.png` or `logo@3x.png`, also
  for `Logo.SVG`.
- **What is exported:** the saved file. Unsaved edits are not included.
- **Size:** 100 % is the SVG's own size, which the preview shows at 100 % (`width`/`height`, or the `viewBox` if
  those are missing). 200 % and 300 % are drawn at that size, not enlarged, so edges and text stay sharp.
- **Look:** the background stays transparent. The SVG is drawn by the same engine as the preview, with its CSS, fonts
  and HTML labels, and its scripts do not run.
- **Overwriting:** if the PNG already exists, a dialog asks whether to replace it. Cancel is the default and writes
  nothing.
- **While it runs:** the export runs in the background and the button is disabled meanwhile. A successful export shows
  no dialog; the PNG appears in the explorer.
- **Errors:** a dialog reports why the export failed, e.g. "Not an SVG image", "Too large at 300%: W × H px" (over 8192
  px per side or about 33 megapixels), "Timed out" (30 s) or "The window closed" (the IDE window closed during the
  export).
