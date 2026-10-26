package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Fortran sources (`.f`, `.for`, `.f77`, `.f90`, `.f95`, `.f03`, `.f08`) with syntax highlighting. */
@Component
class FortranFileViewer :
    CodeFileViewer(
        setOf("f", "for", "f77", "f90", "f95", "f03", "f08"),
        RSyntaxHighlighter.fortran,
        CommentSyntax.BANG,
    )
