package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Clojure sources (`.clj`, `.cljs`, `.cljc`, `.edn`) with syntax highlighting. */
@Component
class ClojureFileViewer :
    CodeFileViewer(
        setOf("clj", "cljs", "cljc", "edn"),
        RSyntaxHighlighter.clojure,
        CommentSyntax.SEMICOLON,
    )
