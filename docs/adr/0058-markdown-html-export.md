# 0058. Markdown export as HTML

- Status: accepted
- Date: 2026-10-18

## Context
Users want to export a Markdown document from its viewer, as HTML first and as PDF later. The HTML file should go next
to the Markdown file under the same name. If it already exists, the user is asked before it is replaced. An optional
stylesheet of the same name can customise it. The file must be self-contained: it references no stylesheets, scripts or
images, so it can be mailed or archived on its own. The preview ([ADR 0016](0016-markdown-preview.md),
[ADR 0017](0017-webview-previews.md)) already renders a page loaded from a string. That page inlines its stylesheet,
its script, KaTeX (with its fonts as `data:` URIs) and Mermaid, and embeds local images.

## Decision
- **The export reuses the preview's renderer.** `MarkdownHtmlExport` takes the sanitised body from `MarkdownRenderer`
  and builds the page with `MarkdownRenderer.page` in a `standalone` mode, so the export looks like the preview.
  Rejected:
  - pandoc `--embed-resources`: an external program the user would have to install, and output that differs from the
    preview;
  - flexmark's exporters: a second Markdown library, already rejected in ADR 0016;
  - a snapshot of the rendered `WebView` DOM: the export would depend on an open, loaded preview and bake in the
    IDE's colour scheme.
- **Self-contained:**
  - Remote `http(s)` images are downloaded at export time (`HttpImageFetcher`: `image/*`, or the file extension if the
    server sends no type, at most 10 MB, 30 s) and embedded. This is the first network access of `file-viewers`.
  - Images that cannot be embedded keep their `src`, and the user is told which. Relative ones still resolve, since
    the HTML sits in the Markdown file's folder.
- **Math and diagrams are drawn during the export, not in the reader's browser.** `WebViewExportRenderer` loads the
  bundled KaTeX and Mermaid into an offscreen `WebEngine` (no `WebView` needed; it lays out SVG text without being
  shown), typesets each formula (`katex.renderToString`, `trust: false`) and draws each diagram twice, in Mermaid's
  `default` and `dark` theme (`securityLevel: 'strict'`, so Mermaid sanitises the SVG as it does in the preview). The
  page inlines the SVGs and KaTeX's HTML verbatim (not parsed and written again, which could change sanitised markup),
  plus KaTeX's stylesheet with its fonts when it has math; the libraries (Mermaid about 3.5 MB, KaTeX about 270 KB) stay
  out. Mermaid prefixes marker and element ids with its render id, so the variants do not collide; sequence-diagram
  actor ids come from a counter that runs on within the one engine of an export. A diagram that cannot be drawn shows
  Mermaid's error and is reported; a formula that cannot be typeset keeps its source and is reported too. Results come
  back one by one, so a timeout keeps what was drawn. Rejected:
  - inlining the libraries, as the preview does: 3.5 MB per page with diagrams;
  - mermaid-cli: Node and Puppeteer the user would have to install;
  - kroki.io or mermaid.ink: the document's diagrams would leave the machine, and the export would need the network;
  - one variant in Mermaid's `neutral` theme: half the size, but light diagrams on a dark page.
- **The colour scheme follows the reader:** the page's only script sets `body.dark` from `prefers-color-scheme` and
  follows its changes; the stylesheet shows the diagram variant of the scheme. The script adds a button that
  toggles the scheme; the choice is not remembered, so the page stores nothing in the reader's browser (storage for
  `file:` pages differs between browsers), and a change of the system setting applies again. Without JavaScript the
  page is light and has no button.
- **Links work in a browser:** the page has no script of the preview, so links and anchors behave as on any page.
  Links to other `.md` files stay as written.
- **Custom stylesheet:** `<name>.css` next to the file is inlined after the built-in styles, so it overrides only what
  it sets. Dark-scheme rules can target `body.dark`.
- **The saved text is exported,** which is what the preview shows.
- **In the background:** rendering, downloading and writing run on a virtual thread; drawing hops to the FX thread
  for the `WebEngine` and is waited for with a timeout of 60 s. The button is disabled meanwhile,
  and problems are reported in a dialog. The overwrite question comes first, on the FX thread, with Cancel as the
  default.
- **Generic hook:** `Preview.exports`, a list of `FileExport`s in `preview/` (ADR 0059; formerly `HtmlExport`). PDF and
  other previews join through the same toolbar menu (`ExportControls`). The file is written to a temporary file
  beside the target and then moved over it, so a failed export keeps the old file; an existing target keeps its
  permissions, and a link is written where it points.

## Consequences
- An exported page with diagrams stays small (tens of KB per diagram); a page with math carries KaTeX's stylesheet and
  fonts, about 430 KB.
- Label sizes are measured with the fonts JavaFX's WebKit finds. A browser with other fonts may make a label a little
  tight; the diagram is the one the IDE's preview shows.
- Exporting a document with remote images needs the network and can take up to 30 s per image that does not answer.
- The exported page carries `data-line` attributes, harmless outside the IDE.
- Added to [../index.md](../index.md).
