package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows CSS stylesheets (`.css`) read-only with syntax highlighting. */
@Component
class CssFileViewer : CodeFileViewer(setOf("css"), RSyntaxHighlighter.css, CommentSyntax.CSS)
