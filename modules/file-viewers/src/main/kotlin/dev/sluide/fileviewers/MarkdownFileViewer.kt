package dev.sluide.fileviewers

import org.springframework.stereotype.Component

/** Shows Markdown and MDX documents (`.md`, `.markdown`, `.mdx`) read-only with syntax highlighting. */
@Component
class MarkdownFileViewer : CodeFileViewer(setOf("md", "markdown", "mdx"), RSyntaxHighlighter.markdown)
