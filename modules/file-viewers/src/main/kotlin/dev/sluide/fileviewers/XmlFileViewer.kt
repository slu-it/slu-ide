package dev.sluide.fileviewers

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows XML files (`.xml`, `.xsd`, `.xsl`, `.xslt`, `.fxml`, IntelliJ's `.iml`) with syntax highlighting. */
@Component
class XmlFileViewer :
    CodeFileViewer(setOf("xml", "xsd", "xsl", "xslt", "fxml", "iml"), RSyntaxHighlighter.xml, CommentSyntax.XML)
