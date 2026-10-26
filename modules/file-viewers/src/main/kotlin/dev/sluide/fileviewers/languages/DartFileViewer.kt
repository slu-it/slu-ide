package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Dart sources (`.dart`) with syntax highlighting. */
@Component
class DartFileViewer :
    CodeFileViewer(
        setOf("dart"),
        RSyntaxHighlighter.dart,
        CommentSyntax.SLASHES,
    )
