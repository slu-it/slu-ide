package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Lisp, Emacs Lisp, Scheme and Racket sources (`.lisp`, `.lsp`, `.el`, `.scm`, `.ss`, `.rkt`) highlighted. */
@Component
class LispFileViewer :
    CodeFileViewer(
        setOf("lisp", "lsp", "el", "scm", "ss", "rkt"),
        RSyntaxHighlighter.lisp,
        CommentSyntax.SEMICOLON,
    )
