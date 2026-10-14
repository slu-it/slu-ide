package dev.sluide.fileviewers

import org.springframework.stereotype.Component

/** Shows JavaScript sources with JSX (`.jsx`) read-only with syntax highlighting. */
@Component
class JsxFileViewer : CodeFileViewer(setOf("jsx"), RSyntaxHighlighter.javaScriptReact)
