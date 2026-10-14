package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows XML files (`.xml`, `.xsd`, `.xsl`, `.xslt`, `.fxml`) read-only with syntax highlighting. */
@Component
class XmlFileViewer :
    CodeFileViewer(setOf("xml", "xsd", "xsl", "xslt", "fxml"), RSyntaxHighlighter.xml, CommentSyntax.XML)
