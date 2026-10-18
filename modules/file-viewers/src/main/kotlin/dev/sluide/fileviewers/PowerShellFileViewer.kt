package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows PowerShell scripts and modules (`.ps1`, `.psm1`, `.psd1`, scripts with a `pwsh` shebang) with highlighting. */
@Component
class PowerShellFileViewer :
    CodeFileViewer(
        setOf("ps1", "psm1", "psd1"),
        RSyntaxHighlighter.powerShell,
        CommentSyntax.POWERSHELL,
        interpreters = setOf("pwsh"),
    )
