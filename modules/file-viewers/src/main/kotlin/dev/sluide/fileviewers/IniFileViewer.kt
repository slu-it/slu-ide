package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows INI files (`.ini`) with syntax highlighting; lines are commented out with `;`. */
@Component
class IniFileViewer : CodeFileViewer(setOf("ini"), RSyntaxHighlighter.ini, CommentSyntax.SEMICOLON)
