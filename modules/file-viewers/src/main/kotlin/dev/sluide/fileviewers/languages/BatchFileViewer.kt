package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Windows batch files (`.bat`, `.cmd`) with syntax highlighting. */
@Component
class BatchFileViewer :
    CodeFileViewer(
        setOf("bat", "cmd"),
        RSyntaxHighlighter.batch,
        CommentSyntax.REM,
    )
