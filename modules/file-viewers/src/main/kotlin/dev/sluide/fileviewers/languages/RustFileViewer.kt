package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Rust sources (`.rs`) with syntax highlighting. */
@Component
class RustFileViewer :
    CodeFileViewer(
        setOf("rs"),
        RSyntaxHighlighter.rust,
        CommentSyntax.SLASHES,
    )
