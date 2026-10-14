package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows JavaScript sources (`.js`, `.mjs`, `.cjs`) read-only with syntax highlighting. */
@Component
class JavaScriptFileViewer :
    CodeFileViewer(setOf("js", "mjs", "cjs"), RSyntaxHighlighter.javaScript, CommentSyntax.SLASHES)
