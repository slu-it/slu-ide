package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Go sources (`.go`) with syntax highlighting. */
@Component
class GoFileViewer :
    CodeFileViewer(
        setOf("go"),
        RSyntaxHighlighter.go,
        CommentSyntax.SLASHES,
    )
