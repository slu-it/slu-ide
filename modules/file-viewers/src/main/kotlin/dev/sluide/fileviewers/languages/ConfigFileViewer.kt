package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/**
 * Shows INI-like configuration files (`.cfg`, `.conf`, `.npmrc`, `.editorconfig`, `.gitconfig`) highlighted like INI
 * files; lines are commented out with `#`, which these formats use rather than `;`.
 */
@Component
class ConfigFileViewer :
    CodeFileViewer(
        setOf("cfg", "conf", "npmrc", "editorconfig", "gitconfig"),
        RSyntaxHighlighter.ini,
        CommentSyntax.HASH,
    )
