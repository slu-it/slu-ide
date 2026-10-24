package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/**
 * Shows the shell's startup files (`.bashrc`, `.bash_profile`, `.zshrc`, `.zprofile`, `.profile`) with syntax
 * highlighting. Scripts to run (`.sh`, `.bash`, `.zsh`, `.ksh`, and shebangs naming a shell) have a viewer of their
 * own in the `shell-scripts` module (ADR 0089).
 */
@Component
class ShellFileViewer :
    CodeFileViewer(
        emptySet(),
        RSyntaxHighlighter.shell,
        CommentSyntax.HASH,
        names = setOf(".bashrc", ".bash_profile", ".zshrc", ".zprofile", ".profile"),
    )
