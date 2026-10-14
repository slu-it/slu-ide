# Markdown preview

**Module:** `file-viewers` — `MarkdownFileViewer`, `MarkdownFileView`, `MarkdownRenderer`; decision in
[ADR 0016](../adr/0016-markdown-preview.md)

- Markdown files (`.md`, `.markdown`) open as highlighted source (left, like every code view) beside a rendered
  preview (right). MDX stays source only: its JSX cannot be rendered.
- A toolbar above switches between **Source**, **Split** (default) and **Preview**. Clicking the selected mode keeps it.
- Rendering: CommonMark with GitHub's tables, strikethrough, task lists, autolinks and heading anchors; fenced code
  with a known language is highlighted like the code views. Relative images resolve against the file's folder.
- Safety: HTML in the file is sanitised (jsoup); scripts, event handlers and `javascript:` links are removed.
- Links never navigate the preview. Anchors (`#part`) scroll within it; `http`, `https` and `mailto` links open in the
  system browser; relative and `file:` links open the file in a SluIde tab (resolved against the Markdown file's folder,
  without fragment or query; nothing happens if no viewer can show it). The page's script calls one Kotlin bridge
  object (`sluideBridge`), which hands the target to the view's `FileViewHost` ([file-viewers.md](file-viewers.md)).
- The preview follows the light or dark theme of the window.
- Side by side, the preview scrolls with the source: the source line at the top of the source pane is brought to the
  top of the preview (interpolated between the rendered blocks, which carry their source line as `data-line`). Only
  this direction: scrolling the preview does not move the source.
- When the file changes on disk, source and preview update (`FileView.reload`); the preview renders only when the text
  changed, then returns to the source's line (side by side) or to its own scroll position (preview alone).
- Modified files open as a diff ([git-diff.md](git-diff.md)) without preview, highlighted as Markdown.
