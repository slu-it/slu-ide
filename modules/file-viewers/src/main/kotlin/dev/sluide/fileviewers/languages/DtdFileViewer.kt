package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows document type definitions (`.dtd`, `.ent`) with syntax highlighting. */
@Component
class DtdFileViewer :
    CodeFileViewer(
        setOf("dtd", "ent"),
        RSyntaxHighlighter.dtd,
        CommentSyntax.XML,
    )
