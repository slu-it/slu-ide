package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows x86 assembler sources (`.asm`, `.s`) with syntax highlighting. */
@Component
class AssemblerFileViewer :
    CodeFileViewer(
        setOf("asm", "s"),
        RSyntaxHighlighter.assembler,
        CommentSyntax.SEMICOLON,
    )
