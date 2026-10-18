package dev.sluide.fileviewers

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows TypeScript sources (`.ts`, `.mts`, `.cts`) read-only with syntax highlighting. */
@Component
class TypeScriptFileViewer :
    CodeFileViewer(setOf("ts", "mts", "cts"), RSyntaxHighlighter.typeScript, CommentSyntax.SLASHES)
