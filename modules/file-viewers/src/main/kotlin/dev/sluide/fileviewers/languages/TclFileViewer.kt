package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Tcl sources (`.tcl`, scripts with a `tclsh` or `wish` shebang) with syntax highlighting. */
@Component
class TclFileViewer :
    CodeFileViewer(
        setOf("tcl"),
        RSyntaxHighlighter.tcl,
        CommentSyntax.HASH,
        interpreters = setOf("tclsh", "wish"),
    )
