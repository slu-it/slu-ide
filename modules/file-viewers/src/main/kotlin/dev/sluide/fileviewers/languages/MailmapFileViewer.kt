package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Git's `.mailmap` with comments and `<e-mail>` addresses highlighted. */
@Component
class MailmapFileViewer :
    CodeFileViewer(emptySet(), RSyntaxHighlighter.mailmap, CommentSyntax.HASH, names = setOf(".mailmap"))
