package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Visual Basic sources and scripts (`.vb`, `.vbs`) with syntax highlighting. */
@Component
class VisualBasicFileViewer :
    CodeFileViewer(
        setOf("vb", "vbs"),
        RSyntaxHighlighter.visualBasic,
        CommentSyntax.APOSTROPHE,
    )
