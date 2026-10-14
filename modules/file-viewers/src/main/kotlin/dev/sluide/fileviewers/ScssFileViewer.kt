package dev.sluide.fileviewers

import org.springframework.stereotype.Component

/** Shows SCSS stylesheets (`.scss`) read-only with syntax highlighting. */
@Component
class ScssFileViewer : CodeFileViewer(setOf("scss"), RSyntaxHighlighter.scss)
