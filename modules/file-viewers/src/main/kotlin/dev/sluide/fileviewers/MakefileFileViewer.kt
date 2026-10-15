package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Makefiles (`Makefile`, `GNUmakefile`, `.mk`) with syntax highlighting. */
@Component
class MakefileFileViewer :
    CodeFileViewer(
        setOf("mk"),
        RSyntaxHighlighter.makefile,
        CommentSyntax.HASH,
        names = setOf("makefile", "gnumakefile"),
    )
