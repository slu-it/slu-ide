package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows D sources (`.d`) with syntax highlighting. */
@Component
class DFileViewer :
    CodeFileViewer(
        setOf("d"),
        RSyntaxHighlighter.d,
        CommentSyntax.SLASHES,
    )
