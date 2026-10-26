package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Go module files (`go.mod`, `go.work`) with syntax highlighting. */
@Component
class GoModFileViewer :
    CodeFileViewer(
        emptySet(),
        RSyntaxHighlighter.goMod,
        CommentSyntax.LINE_SLASHES,
        names = setOf("go.mod", "go.work"),
    )
