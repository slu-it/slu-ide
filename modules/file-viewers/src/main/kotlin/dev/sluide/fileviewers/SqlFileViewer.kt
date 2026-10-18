package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows SQL scripts (`.sql`) with syntax highlighting. */
@Component
class SqlFileViewer : CodeFileViewer(setOf("sql"), RSyntaxHighlighter.sql, CommentSyntax.SQL)
