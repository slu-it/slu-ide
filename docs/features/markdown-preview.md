# Markdown preview

**Module:** `file-viewers` — `MarkdownFileViewer` (a `PreviewFileViewer`), `PreviewFileView`, `MarkdownRenderer`, `TableOfContents`,
`MarkdownOutline`; decision in
[ADR 0016](../adr/0016-markdown-preview.md)

- Markdown files (`.md`, `.markdown`) open as highlighted source (left, like every code view) beside a rendered
  preview (right). MDX stays source only: its JSX cannot be rendered.
- A toolbar above switches between **Source**, **Split** (default) and **Preview**. Clicking the selected mode keeps it.
- Rendering: CommonMark with GitHub's tables, strikethrough, task lists, autolinks and heading anchors; fenced code
  with a known language is highlighted like the code views.
- Math, typeset by KaTeX (bundled, works offline): `$…$` inline (no space inside the dollars, no digit after the
  closing one, so `$5 and $6` stays text; `\$` is a dollar sign), `$$…$$` (also within a paragraph) and blocks
  between `$$` lines as display math, and ` ```math ` fences. Like a code fence, a `$$` line without a closing one
  runs to the end of the file.
- Diagrams: ` ```mermaid ` fences are drawn by Mermaid (bundled, works offline, `securityLevel: 'strict'`), in the
  light or dark Mermaid theme, without a code box behind them in either theme; a theme change draws them again.
  Invalid diagrams show Mermaid's error instead. In the source, ` ```mermaid ` and ` ```mmd ` fences are highlighted
  as Mermaid; Mermaid files of their own: [previews.md](previews.md).
- Images: local ones (relative to the file's folder, or `file:`; png, jpg, gif, svg, webp, bmp, ico up to 10 MB) are
  embedded into the page, since a page loaded from a string may not read files. Larger ones stay as written and do
  not show. The 10 MB are per image: a file with many large images makes a large page, read and encoded on the JavaFX
  thread every time it renders. Remote (`https`) images load from the web when the preview renders, as on GitHub.
- Safety: HTML in the file is sanitised (jsoup); scripts, event handlers, styles, frames, forms, inputs other than
  task-list checkboxes, SVG and `javascript:`/`data:` links are removed. Should the preview still end up on another
  page, it stops with a notice and **Show the page again** ([previews.md](previews.md)), and only its own page can
  reach the link bridge.
- Links never navigate the preview. Anchors (`#part`) scroll within it; `http`, `https`, `mailto` and scheme-relative
  (`//host/…`, as `https`) links open in the system browser; relative and `file:` links open the file in a Thoreau tab,
  wherever it is (resolved against the Markdown file's folder, without fragment or query; nothing happens if
  no viewer can show it). The page's script calls one Kotlin bridge
  object (`sluideBridge`), which hands the target to the view's `FileViewHost` ([file-viewers.md](file-viewers.md)).
- The preview follows the light or dark theme of the window.
- Side by side, the preview scrolls with the source: the source line at the top of the source pane is brought to the
  top of the preview (interpolated between the rendered blocks, which carry their source line as `data-line`). Only
  this direction: scrolling the preview does not move the source.
- When the file changes on disk, source and preview update (`FileView.reload`); the preview renders only when the text
  changed, then returns to the source's line (side by side) or to its own scroll position (preview alone).
- The divider keeps its place when switching modes.
- Table of contents: a toggle at the left of the toolbar (Markdown only) opens an overlay that floats over the top left
  of the source/preview split, in all three modes; it is hidden by default. It lists every heading (also in lists and
  quotes; `#` lines in fenced code or math are none), indented by the rank of its level among the levels the document
  uses (only `##` and `####`: two depths). On opening, the entry of the section with the caret (in Preview mode, with the
  line at the top of the preview) is selected and scrolled into view, and the list has the focus. The entries are always
  current when it opens; while it is open, changes that do not come from the user's own input (e.g. a reload from disk)
  update the entries and the selection (not in Preview mode). Choosing an entry (click, or Enter) puts the
  caret on the heading and brings it to the top of the source; side by side the preview follows by the scroll sync,
  in Preview mode it scrolls to the heading. The overlay closes on choosing, Escape, the toggle and a click elsewhere
  in the view; Escape and the toggle give the focus back to the source (the preview in Preview mode). The preview shows
  the saved text, so with unsaved edits above a heading it may land a little off, as the scroll sync does.
- Known gaps: the diff of a modified file ([git-diff.md](git-diff.md)) has no preview, it is highlighted as Markdown.
  Scroll sync goes from source to preview only.
