package dev.sluide.previews.xml

import dev.sluide.previews.preview.Formatted

/**
 * Formats XML as plain text, the way [XmlRenderer] shows it: 2-space indent, start tags with attributes as written,
 * `<v>text</v>` for elements with only text or CDATA, `<x/>` for empty ones, comments, processing instructions, CDATA
 * and the DOCTYPE kept, trimmed text on its own lines, no `<?xml?>` declaration, a trailing newline. Text and attribute
 * values are escaped again. Unlike the page it has no node cap: the cap only protects the DOM.
 */
internal object XmlText {
    fun format(text: String): Formatted {
        // Editors may save a byte order mark.
        val source = text.removePrefix("\uFEFF")
        val parsed = if (source.isBlank()) null else XmlParser.parse(source, Int.MAX_VALUE)
        return when {
            parsed?.error != null -> Formatted.Failed(parsed.error)
            parsed == null || parsed.nodes.isEmpty() -> Formatted.Failed("The file is empty")
            else -> Formatted.Text(StringBuilder(source.length + 64).also { it.writeAll(parsed.nodes, 0) }.toString())
        }
    }

    private fun StringBuilder.writeAll(
        nodes: List<XmlNode>,
        depth: Int,
    ) {
        for (node in nodes) {
            repeat(depth) { append("  ") }
            when (node) {
                is XmlElement -> writeElement(node, depth)
                else -> writeInline(node)
            }
            append('\n')
        }
    }

    private fun StringBuilder.writeElement(
        element: XmlElement,
        depth: Int,
    ) {
        val only = element.children.singleOrNull()
        when {
            element.children.isEmpty() -> {
                writeStartTag(element, true)
            }

            only is XmlTextNode || only is XmlCdata -> {
                writeStartTag(element, false)
                writeInline(only)
                append("</").append(element.name).append('>')
            }

            else -> {
                writeStartTag(element, false)
                append('\n')
                writeAll(element.children, depth + 1)
                repeat(depth) { append("  ") }
                append("</").append(element.name).append('>')
            }
        }
    }

    private fun StringBuilder.writeStartTag(
        element: XmlElement,
        selfClosing: Boolean,
    ) {
        append('<').append(element.name)
        for ((name, value) in element.attributes) {
            append(' ')
                .append(name)
                .append("=\"")
                .append(attributeValue(value))
                .append('"')
        }
        append(if (selfClosing) "/>" else ">")
    }

    private fun StringBuilder.writeInline(node: XmlNode) {
        when (node) {
            is XmlTextNode -> append(text(node.text))
            is XmlCdata -> append("<![CDATA[").append(node.text).append("]]>")
            is XmlComment -> append("<!--").append(node.text).append("-->")
            is XmlInstruction -> append("<?").append(node.text).append("?>")
            is XmlDoctype -> append(node.text)
            is XmlElement -> error("elements are written by writeElement")
        }
    }

    /** Text as it must be written to read back the same: `]]>` and a carriage return (else a line break) escaped. */
    private fun text(text: String) = xmlEscape(text, quotes = false).replace("]]>", "]]&gt;").replace("\r", "&#13;")

    /** An attribute value with tab and line breaks as character references (else they read back as spaces). */
    private fun attributeValue(value: String) =
        xmlEscape(value, quotes = true).replace("\t", "&#9;").replace("\n", "&#10;").replace("\r", "&#13;")
}
