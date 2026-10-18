package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows SCSS stylesheets (`.scss`) read-only with syntax highlighting. */
@Component
class ScssFileViewer : CodeFileViewer(setOf("scss"), RSyntaxHighlighter.scss, CommentSyntax.SLASHES)
