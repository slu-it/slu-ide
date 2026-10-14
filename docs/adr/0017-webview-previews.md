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

## Consequences
- A new rendered format is a `Preview` and a `PreviewFileViewer` subclass.
- HTML previews show pages as browsers would, but without the page's scripts unless allowed; pages that build their
  content with scripts look empty until then.
- Opening an HTML file reads the files it references from disk; with scripts allowed, it may also reach the web.
