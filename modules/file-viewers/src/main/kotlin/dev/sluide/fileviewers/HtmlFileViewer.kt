package dev.sluide.fileviewers

import org.springframework.stereotype.Component

/**
 * Shows HTML files (`.html`, `.htm`, `.xhtml`) read-only with syntax highlighting, including embedded scripts and
 * styles.
 */
@Component
class HtmlFileViewer : CodeFileViewer(setOf("html", "htm", "xhtml"), RSyntaxHighlighter.html)
