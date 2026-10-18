package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/**
 * Shows JavaScript sources (`.js`, `.mjs`, `.cjs`, scripts with a `node`, `deno` or `bun` shebang) with syntax
 * highlighting.
 */
@Component
class JavaScriptFileViewer :
    CodeFileViewer(
        setOf("js", "mjs", "cjs"),
        RSyntaxHighlighter.javaScript,
        CommentSyntax.SLASHES,
        interpreters = setOf("node", "deno", "bun"),
    )
