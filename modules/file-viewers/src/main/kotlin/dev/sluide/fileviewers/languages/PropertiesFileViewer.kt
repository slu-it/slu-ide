package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Java properties files (`.properties`, also `gradle.properties`); lines are commented out with `#`. */
@Component
class PropertiesFileViewer : CodeFileViewer(setOf("properties"), RSyntaxHighlighter.properties, CommentSyntax.HASH)
