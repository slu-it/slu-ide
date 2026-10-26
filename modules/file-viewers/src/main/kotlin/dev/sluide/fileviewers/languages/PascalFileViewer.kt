package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Pascal and Delphi sources (`.pas`, `.dpr`, `.lpr`) with syntax highlighting. */
@Component
class PascalFileViewer :
    CodeFileViewer(
        setOf("pas", "dpr", "lpr"),
        RSyntaxHighlighter.pascal,
        CommentSyntax.PASCAL,
    )
