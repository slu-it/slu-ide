package dev.sluide.fileviewers

import org.springframework.stereotype.Component

/** Shows YAML files (`.yaml`, `.yml`) read-only with syntax highlighting. */
@Component
class YamlFileViewer : CodeFileViewer(setOf("yaml", "yml"), RSyntaxHighlighter.yaml)
