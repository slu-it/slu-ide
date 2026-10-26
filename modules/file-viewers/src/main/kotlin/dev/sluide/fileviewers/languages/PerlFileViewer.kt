package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Perl sources (`.pl`, `.pm`, scripts with a `perl` shebang) with syntax highlighting. */
@Component
class PerlFileViewer :
    CodeFileViewer(
        setOf("pl", "pm"),
        RSyntaxHighlighter.perl,
        CommentSyntax.HASH,
        interpreters = setOf("perl"),
    )
