package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Kotlin sources and scripts (`.kt`, `.kts`) read-only with syntax highlighting. */
@Component
class KotlinFileViewer : CodeFileViewer(setOf("kt", "kts"), RSyntaxHighlighter.kotlin, CommentSyntax.SLASHES)
