package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.KqlHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Kusto queries and commands (`.kql`, `.csl`) with syntax highlighting ([KqlHighlighter]). */
@Component
class KqlFileViewer : CodeFileViewer(setOf("kql", "csl"), KqlHighlighter, CommentSyntax.LINE_SLASHES)
