package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Groovy sources and Gradle's Groovy DSL scripts (`.groovy`, `.gradle`) read-only with syntax highlighting. */
@Component
class GroovyFileViewer : CodeFileViewer(setOf("groovy", "gradle"), RSyntaxHighlighter.groovy, CommentSyntax.SLASHES)
