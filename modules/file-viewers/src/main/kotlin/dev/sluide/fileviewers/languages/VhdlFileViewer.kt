package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows VHDL sources (`.vhd`, `.vhdl`) with syntax highlighting. */
@Component
class VhdlFileViewer :
    CodeFileViewer(
        setOf("vhd", "vhdl"),
        RSyntaxHighlighter.vhdl,
        CommentSyntax.DOUBLE_DASH,
    )
