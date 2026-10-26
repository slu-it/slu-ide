package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows PHP sources (`.php`, `.phtml`, scripts with a `php` shebang) with syntax highlighting. */
@Component
class PhpFileViewer :
    CodeFileViewer(
        setOf("php", "phtml"),
        RSyntaxHighlighter.php,
        CommentSyntax.SLASHES,
        interpreters = setOf("php"),
    )
