package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/**
 * Shows shell scripts (`.sh`, `.bash`, `.zsh`, `.ksh`, startup files like `.bashrc`, scripts with an `sh`, `bash`,
 * `zsh`, `dash` or `ksh` shebang) with syntax highlighting.
 */
@Component
class ShellFileViewer :
    CodeFileViewer(
        setOf("sh", "bash", "zsh", "ksh"),
        RSyntaxHighlighter.shell,
        CommentSyntax.HASH,
        names = setOf(".bashrc", ".bash_profile", ".zshrc", ".zprofile", ".profile"),
        interpreters = setOf("sh", "bash", "zsh", "dash", "ksh"),
    )
