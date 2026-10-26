package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/**
 * Shows JSON5 files (`.json5`) highlighted as JSON, without a preview; lines are commented out with `//`. Known gap:
 * the JSON lexer does not know JSON5's unquoted keys and single-quoted strings.
 */
@Component
class Json5FileViewer : CodeFileViewer(setOf("json5"), RSyntaxHighlighter.json, CommentSyntax.SLASHES)
