package dev.sluide.fileviewers

import org.springframework.stereotype.Component

/** Shows Markdown documents (`.md`, `.markdown`) read-only with syntax highlighting. */
@Component
class MarkdownFileViewer : CodeFileViewer(setOf("md", "markdown"), RSyntaxHighlighter.markdown)
