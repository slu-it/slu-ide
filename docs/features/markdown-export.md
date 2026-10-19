# Markdown export

**Module:** `file-viewers`: `MarkdownHtmlExport`, `HttpImageFetcher`, `WebViewExportRenderer`, `ExportControls`
(toolbar of `PreviewFileView`).
Decision in [ADR 0058](../adr/0058-markdown-html-export.md).

- **Export → HTML** in the toolbar of the Markdown preview writes `<name>.html` next to `<name>.md` (or
  `<name>.markdown`). It exports the saved text, which is what the preview shows. PDF is to follow in the same menu.
- If `<name>.html` already exists, a dialog asks whether to replace it. Cancel is the default and writes nothing.
- The page looks like the preview: the same rendering, sanitising, code highlighting, math (KaTeX) and diagrams
  (Mermaid).
- **Self-contained:** styles and fonts are inlined, and images are embedded as `data:` URIs:
  - local images up to 10 MB;
  - remote `http(s)` images, downloaded at export time (`image/*`, or the file extension if the server sends no type; up
    to 10 MB each, 30 s timeout).

  If an image cannot be embedded, it keeps its `src`, and a dialog lists it after the export. Relative images still
  show while the HTML stays in the Markdown file's folder.
- **Math and diagrams are drawn during the export:** formulas are typeset by KaTeX and diagrams drawn by Mermaid in an
  offscreen web engine, and the page holds the result (HTML and SVG), not the libraries. Each diagram is drawn in the
  light and the dark Mermaid theme; the page shows the one of the reader's scheme. A diagram is some tens of KB; a page
  with math carries KaTeX's stylesheet and fonts (about 430 KB). A diagram that cannot be drawn shows Mermaid's error in
  the page and is listed in the dialog after the export; math that cannot be typeset keeps its TeX source and is
  reported as well. Labels are measured with the IDE's fonts, so in a browser with other fonts a label may sit a little
  tight.
- **Custom styles:** `<name>.css` next to the Markdown file is inlined after the built-in styles, so it overrides only
  what it sets. In the reader's dark scheme the body has the class `dark`, so dark rules use `body.dark …`.
- **Colour scheme:** the page follows the reader's light or dark setting (`prefers-color-scheme`), also when it changes,
  not the IDE's theme. A button at the top right switches between light and dark for as long as the page is open;
  a change of the system setting takes over again. The button is not printed. That script is the page's only one;
  without JavaScript the page is light and has no button.
- **Links** work as in any page in a browser. Anchors scroll within the page. Links to other Markdown files stay as
  written: they lead to the `.md` file, not to its export.
- Rendering, downloading and writing run in the background, and the button is disabled meanwhile. A successful export
  shows no dialog; the new file appears in the explorer. Failures to write are shown in a dialog.
