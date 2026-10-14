# Markdown preview

**Module:** `file-viewers` — `MarkdownFileViewer`, `MarkdownFileView`, `MarkdownRenderer`; decision in
[ADR 0016](../adr/0016-markdown-preview.md)

- Markdown files (`.md`, `.markdown`) open as highlighted source (left, like every code view) beside a rendered
  preview (right). MDX stays source only: its JSX cannot be rendered.
- A toolbar above switches between **Source**, **Split** (default) and **Preview**. Clicking the selected mode keeps it.
- Rendering: CommonMark with GitHub's tables, strikethrough, task lists, autolinks and heading anchors; fenced code
  with a known language is highlighted like the code views. Relative images resolve against the file's folder.
- Safety: HTML in the file is sanitised (jsoup); scripts, event handlers and `javascript:` links are removed.
- The preview follows the light or dark theme of the window.
- When the file changes on disk, source and preview update (`FileView.reload`); the preview renders only when the text
  changed.
- Modified files open as a diff ([git-diff.md](git-diff.md)) without preview, highlighted as Markdown.
