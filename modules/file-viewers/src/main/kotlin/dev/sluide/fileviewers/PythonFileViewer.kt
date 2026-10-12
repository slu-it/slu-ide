package dev.sluide.fileviewers

import org.springframework.stereotype.Component

/** Shows Python sources (`.py`, `.pyw`, `.pyi`) read-only with syntax highlighting. */
@Component
class PythonFileViewer : CodeFileViewer(setOf("py", "pyw", "pyi"), RSyntaxHighlighter.python)
