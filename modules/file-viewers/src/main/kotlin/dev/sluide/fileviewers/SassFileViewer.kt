package dev.sluide.fileviewers

import org.springframework.stereotype.Component

/** Shows Sass stylesheets in the indented syntax (`.sass`) read-only with syntax highlighting. */
@Component
class SassFileViewer : CodeFileViewer(setOf("sass"), RSyntaxHighlighter.sass)
