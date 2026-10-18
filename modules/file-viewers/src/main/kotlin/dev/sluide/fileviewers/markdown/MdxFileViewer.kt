package dev.sluide.fileviewers.markdown

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows MDX documents (`.mdx`, Markdown with JSX) read-only with syntax highlighting. */
@Component
class MdxFileViewer : CodeFileViewer(setOf("mdx"), RSyntaxHighlighter.mdx, CommentSyntax.XML)
