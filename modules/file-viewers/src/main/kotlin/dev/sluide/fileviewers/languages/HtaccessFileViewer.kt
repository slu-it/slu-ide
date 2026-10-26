package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Apache `.htaccess` files, and files like `site.htaccess`, with syntax highlighting. */
@Component
class HtaccessFileViewer :
    CodeFileViewer(
        setOf("htaccess"),
        RSyntaxHighlighter.htaccess,
        CommentSyntax.HASH,
    )
