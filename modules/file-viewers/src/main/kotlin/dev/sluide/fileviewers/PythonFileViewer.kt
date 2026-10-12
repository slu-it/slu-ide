package dev.sluide.fileviewers

import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.springframework.stereotype.Component

/** Shows Python sources (`.py`, `.pyw`, `.pyi`) read-only with syntax highlighting. */
@Component
class PythonFileViewer : CodeFileViewer(setOf("py", "pyw", "pyi"), highlighter) {
    internal companion object {
        /** The lexer predates Python 3 in places; these corrections bring it up to date. */
        val highlighter =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_PYTHON,
                keywords = setOf("async", "await", "nonlocal"),
                builtins = setOf("print", "exec"),
                stringPrefixes = setOf("f", "r", "b", "u", "rb", "br", "fr", "rf"),
            )
    }
}
