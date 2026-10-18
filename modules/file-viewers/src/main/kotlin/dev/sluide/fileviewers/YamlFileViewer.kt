package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows YAML files (`.yaml`, `.yml`) read-only with syntax highlighting. */
@Component
class YamlFileViewer : CodeFileViewer(setOf("yaml", "yml"), RSyntaxHighlighter.yaml, CommentSyntax.HASH)
