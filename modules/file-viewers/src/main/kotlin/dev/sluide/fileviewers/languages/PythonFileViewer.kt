package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Python sources (`.py`, `.pyw`, `.pyi`, scripts with a `python`/`python3` shebang) with syntax highlighting. */
@Component
class PythonFileViewer :
    CodeFileViewer(
        setOf("py", "pyw", "pyi"),
        RSyntaxHighlighter.python,
        CommentSyntax.HASH,
        interpreters = setOf("python", "python3"),
    )
