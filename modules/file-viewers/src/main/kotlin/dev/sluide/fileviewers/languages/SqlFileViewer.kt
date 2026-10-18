package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows SQL scripts (`.sql`) with syntax highlighting. */
@Component
class SqlFileViewer : CodeFileViewer(setOf("sql"), RSyntaxHighlighter.sql, CommentSyntax.SQL)
