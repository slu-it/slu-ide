package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows JSON files (`.json`, `.jsonc`) read-only with syntax highlighting. */
@Component
class JsonFileViewer : CodeFileViewer(setOf("json", "jsonc"), RSyntaxHighlighter.json, CommentSyntax.NONE)
