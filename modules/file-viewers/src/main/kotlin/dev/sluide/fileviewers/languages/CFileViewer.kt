package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows C sources (`.c`) with syntax highlighting. */
@Component
class CFileViewer :
    CodeFileViewer(
        setOf("c"),
        RSyntaxHighlighter.c,
        CommentSyntax.SLASHES,
    )
