package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows LESS stylesheets (`.less`) read-only with syntax highlighting. */
@Component
class LessFileViewer : CodeFileViewer(setOf("less"), RSyntaxHighlighter.less, CommentSyntax.SLASHES)
