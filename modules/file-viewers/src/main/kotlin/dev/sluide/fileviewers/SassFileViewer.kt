package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Sass stylesheets in the indented syntax (`.sass`) read-only with syntax highlighting. */
@Component
class SassFileViewer : CodeFileViewer(setOf("sass"), RSyntaxHighlighter.sass, CommentSyntax.SLASHES)
