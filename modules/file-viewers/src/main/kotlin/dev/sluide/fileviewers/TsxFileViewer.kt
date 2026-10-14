package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows TypeScript sources with JSX (`.tsx`) read-only with syntax highlighting. */
@Component
class TsxFileViewer : CodeFileViewer(setOf("tsx"), RSyntaxHighlighter.typeScriptReact, CommentSyntax.SLASHES)
