package dev.sluide.fileviewers

import org.springframework.stereotype.Component

/** Shows XML files (`.xml`, `.xsd`, `.xsl`, `.xslt`, `.svg`, `.fxml`) read-only with syntax highlighting. */
@Component
class XmlFileViewer : CodeFileViewer(setOf("xml", "xsd", "xsl", "xslt", "svg", "fxml"), RSyntaxHighlighter.xml)
