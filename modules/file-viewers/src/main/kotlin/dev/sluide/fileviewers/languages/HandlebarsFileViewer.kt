package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Handlebars and Mustache templates (`.hbs`, `.handlebars`, `.mustache`) with syntax highlighting. */
@Component
class HandlebarsFileViewer :
    CodeFileViewer(
        setOf("hbs", "handlebars", "mustache"),
        RSyntaxHighlighter.handlebars,
        CommentSyntax.HANDLEBARS,
    )
