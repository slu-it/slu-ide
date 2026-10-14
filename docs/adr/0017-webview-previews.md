# 0017. WebView previews beyond Markdown

- Status: accepted
- Date: 2026-10-14

## Context
With `javafx.web` in the app for the Markdown preview ([ADR 0016](0016-markdown-preview.md)), other formats can be
rendered the same way: HTML pages, SVG and images, CSV tables and Jupyter notebooks, and diagrams and math in
Markdown. They need the same split view, theme handling, reload and link handling. HTML files are the special case:
they come with their own scripts and links and are shown as their author made them.

## Decision
- One view for all of them: `PreviewFileView` shows a `CodeView` beside a `WebView`, with the Source / Split / Preview
  toolbar; a `Preview` strategy decides how a file is loaded, whether the page brings our script (theme, link bridge,
  scroll sync) and whether it offers to run the page's own scripts. Viewers extend `PreviewFileViewer`.
- **HTML** is loaded from disk (`WebEngine.load`), not rendered by us, so relative resources work. Its scripts are off
  by default (`WebEngine.isJavaScriptEnabled`); a toolbar toggle runs them for one tab. Because such a page has no
  script of ours, links are caught as navigations: any location other than the file itself (with any anchor) is
  turned back and handed to the view's host, so the preview never shows another page.

- **SVG and images** are shown as an `<img>` with a `data:` URI on a page of ours (`ImagePage`): no file access from the
  page, and an SVG's scripts never run. Images are the first binary files SluIde opens; `ImageFileViewer` accepts them
  without the text check, up to 50 MB.

- **JavaScript libraries** for the previews are bundled in `file-viewers` (`vendor/`, versions and checksums in its
  README), not loaded from the web: previews work offline and show the same in every version. Pages are loaded from
  strings and cannot reference files in the jar, so a page gets a library inline, and only when it needs it.
  - **KaTeX** (MIT) typesets Markdown math (`MarkdownMath`, a commonmark extension for `$…$`, `$$` blocks and
    ` ```math `). Its fonts are inlined as woff `data:` URIs; JavaFX's WebKit does not load woff2 that way.

  - **Mermaid** (MIT, 3.5 MB) draws ` ```mermaid ` fences with `securityLevel: 'strict'` (no HTML in labels, no
    click handlers), from the source kept in `data-source` so that a theme change can draw them again.

- **Notebooks** are rendered by us from their JSON (Jackson, from the Spring Boot BOM): Markdown cells through the
  Markdown renderer, code highlighted, stored outputs shown. HTML outputs go through the same sanitiser as Markdown,
  images as `data:` URIs; nothing in a notebook runs.

## Consequences
- A new rendered format is a `Preview` and a `PreviewFileViewer` subclass.
- HTML previews show pages as browsers would, but without the page's scripts unless allowed; pages that build their
  content with scripts look empty until then.
- Opening an HTML file reads the files it references from disk; with scripts allowed, it may also reach the web.
- The bundled libraries add about 4 MB to the jar (Mermaid 3.5 MB, KaTeX with fonts 0.6 MB); a page with a diagram
  carries Mermaid's 3.5 MB inline, which takes a moment to load. Updating them is manual (`vendor/README.md`).
