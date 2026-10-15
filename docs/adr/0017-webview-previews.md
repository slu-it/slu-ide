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
- **HTML** is loaded by URL (`WebEngine.load`), not rendered by us, so relative resources work: first as a `file:`
  URL, since [ADR 0018](0018-html-preview-server.md) from a local server. Its scripts are off by default
  (`WebEngine.isJavaScriptEnabled`); a toolbar toggle runs them for one tab after a warning. Because such a page has
  no script of ours, links are caught as navigations: a location other than the file itself (with any anchor) never
  shows. Within 2 seconds of the user's mouse press, key press or drop in the preview it is handed to the view's
  host and the file is shown again; otherwise (meta refresh, scripts) the preview stops with a notice until the user
  or a change of the file shows it again, so a page can neither open things on its own nor loop. The same goes for a
  page that loads itself again.

- **SVG and images** are shown as an `<img>` with a `data:` URI on a page of ours (`ImagePage`): no file access from the
  page, and an SVG's scripts never run. Images are the first binary files Thoreau opens; `ImageFileViewer` accepts them
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
- Opening an HTML file loads what it references, from disk and from the web (tracking images work), even with
  scripts off.
- With scripts on, a `file:` page can read other local files (JavaFX's `WebEngine` cannot forbid it) and send them
  to the web. [ADR 0018](0018-html-preview-server.md) serves pages from a local server instead, which confines them to
  the project.
- The fat jar grows from 78 MB to 82 MB (Mermaid 3.5 MB, KaTeX with fonts 0.6 MB, Jackson); a page with a diagram
  carries Mermaid's 3.5 MB inline, which is parsed again each time the file changes, a noticeable moment with an
  editor that saves often. Updating the libraries is manual (`vendor/README.md`).
