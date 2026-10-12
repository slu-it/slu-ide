package dev.sluide.fileviewers

import org.springframework.stereotype.Component

/** Shows JSON files (`.json`, `.jsonc`) read-only with syntax highlighting. */
@Component
class JsonFileViewer : CodeFileViewer(setOf("json", "jsonc"), RSyntaxHighlighter.json)
