package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows JavaServer Pages and their tag files (`.jsp`, `.jspf`, `.tag`) with syntax highlighting. */
@Component
class JspFileViewer :
    CodeFileViewer(
        setOf("jsp", "jspf", "tag"),
        RSyntaxHighlighter.jsp,
        CommentSyntax.JSP,
    )
