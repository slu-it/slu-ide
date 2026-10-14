package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Python sources (`.py`, `.pyw`, `.pyi`) read-only with syntax highlighting. */
@Component
class PythonFileViewer : CodeFileViewer(setOf("py", "pyw", "pyi"), RSyntaxHighlighter.python, CommentSyntax.HASH)
