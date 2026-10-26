package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Scala sources and scripts (`.scala`, `.sc`, scripts with a `scala` shebang) with syntax highlighting. */
@Component
class ScalaFileViewer :
    CodeFileViewer(
        setOf("scala", "sc"),
        RSyntaxHighlighter.scala,
        CommentSyntax.SLASHES,
        interpreters = setOf("scala", "scala-cli"),
    )
