package dev.sluide.fileviewers

import org.springframework.stereotype.Component

/** Shows TypeScript sources (`.ts`, `.mts`, `.cts`) read-only with syntax highlighting. */
@Component
class TypeScriptFileViewer : CodeFileViewer(setOf("ts", "mts", "cts"), RSyntaxHighlighter.typeScript)
