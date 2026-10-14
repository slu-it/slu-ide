package dev.sluide.fileviewers

import org.springframework.stereotype.Component

/** Shows CSS stylesheets (`.css`) read-only with syntax highlighting. */
@Component
class CssFileViewer : CodeFileViewer(setOf("css"), RSyntaxHighlighter.css)
