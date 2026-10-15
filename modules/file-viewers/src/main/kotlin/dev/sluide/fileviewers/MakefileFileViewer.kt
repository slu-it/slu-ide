package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import dev.sluide.textediting.IndentStyle
import org.springframework.stereotype.Component

/**
 * Shows Makefiles (`Makefile`, `GNUmakefile`, `.mk`) with syntax highlighting. Recipe lines must start with a tab, so
 * Makefiles are indented with tabs unless `.editorconfig` or their text say otherwise.
 */
@Component
class MakefileFileViewer :
    CodeFileViewer(
        setOf("mk"),
        RSyntaxHighlighter.makefile,
        CommentSyntax.HASH,
        names = setOf("makefile", "gnumakefile"),
    ) {
    override val defaultIndent: IndentStyle = IndentStyle.DEFAULT.copy(useTabs = true)
}
