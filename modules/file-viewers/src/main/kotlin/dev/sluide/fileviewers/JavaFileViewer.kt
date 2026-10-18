package dev.sluide.fileviewers

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Java sources (`.java`) read-only with syntax highlighting. */
@Component
class JavaFileViewer : CodeFileViewer(setOf("java"), RSyntaxHighlighter.java, CommentSyntax.SLASHES)
