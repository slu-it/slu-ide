package dev.sluide.fileviewers

import org.springframework.stereotype.Component

/** Shows Java sources (`.java`) read-only with syntax highlighting. */
@Component
class JavaFileViewer : CodeFileViewer(setOf("java"), RSyntaxHighlighter.java)
