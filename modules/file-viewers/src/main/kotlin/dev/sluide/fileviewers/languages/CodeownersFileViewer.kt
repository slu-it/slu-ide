package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows GitHub's `CODEOWNERS` with comments, pattern wildcards and owners (`@user`, `@org/team`, e-mails). */
@Component
class CodeownersFileViewer :
    CodeFileViewer(emptySet(), RSyntaxHighlighter.codeowners, CommentSyntax.HASH, names = setOf("codeowners"))
