package dev.sluide.fileviewers.xml

import dev.sluide.fileviewers.preview.HtmlPage
import dev.sluide.fileviewers.preview.appendFoldState
import java.util.Locale

/**
 * Renders XML as a formatted page for the preview (ADR 0065): one line per node, every element with child elements or
 * mixed content a `<details>` that can be folded, with its path (`data-path`,
 * `/project[1]/dependencies[1]/dependency[2]`, an index at every step, for `collapsed` and the page's
 * `sluide.folds.collapsed()`) and the source line it starts on (`data-line`, 0-based like the other previews, for
 * scroll sync). A page that is not complete (empty, with an error, or cut off at [MAX_NODES]) also keeps the
 * `collapsed` it was given in a hidden `#fold-state` element, so folds survive
 * an invalid save in between. Comments, processing instructions, CDATA and the DOCTYPE are kept; the `<?xml?>`
 * declaration and whitespace-only text are dropped. Parsing: [XmlParser].
 */
internal object XmlRenderer {
    /** The most nodes (elements, text, comments, …) a page shows; the rest of a larger file is left out with a note. */
    const val MAX_NODES = 100_000

    private val stylesheet = HtmlPage.resource("preview.css")
    private val treeStylesheet = HtmlPage.resource("fold-tree.css")
    private val script = HtmlPage.resource("preview.js")
    private val treeScript = HtmlPage.resource("fold-tree.js")

    /** The formatted page of [text]; elements whose path is in [collapsed] start closed. */
    fun render(
        text: String,
        dark: Boolean,
        collapsed: Set<String> = emptySet(),
    ): String = render(text, dark, collapsed, MAX_NODES)

    internal fun render(
        text: String,
        dark: Boolean,
        collapsed: Set<String>,
        maxNodes: Int,
    ): String {
        // Editors may save a byte order mark.
        val source = text.removePrefix("\uFEFF")
        val parsed = if (source.isBlank()) null else XmlParser.parse(source, maxNodes)
        return HtmlPage.render(
            dark,
            listOf(stylesheet, treeStylesheet),
            listOf(script, treeScript),
            listOf("fold-page"),
        ) {
            val complete = parsed != null && parsed.nodes.isNotEmpty() && parsed.error == null && !parsed.truncated
            if (collapsed.isNotEmpty() && !complete) appendFoldState(collapsed)
            if (parsed == null || (parsed.nodes.isEmpty() && parsed.error == null)) {
                append("<p class=\"fold-note\">Empty file</p>")
            } else {
                if (parsed.error != null) {
                    append("<p class=\"fold-error\">").append(HtmlPage.escape(parsed.error)).append("</p>")
                }
                // No line breaks between the blocks: they would show as blank lines.
                append("<div class=\"fold-tree\">")
                writeNodes(parsed.nodes, "", collapsed)
                append("</div>")
                if (parsed.truncated) {
                    append("<p class=\"fold-note\">Showing the first ")
                        .append("%,d".format(Locale.ROOT, maxNodes))
                        .append(" nodes.</p>")
                }
            }
        }
    }

    private fun StringBuilder.writeNodes(
        nodes: List<XmlNode>,
        path: String,
        collapsed: Set<String>,
    ) {
        val seen = HashMap<String, Int>()
        for (node in nodes) {
            when (node) {
                is XmlElement -> {
                    val index = seen.merge(node.name, 1, Int::plus)
                    writeElement(node, "$path/${node.name}[$index]", collapsed)
                }

                else -> {
                    append("<div>")
                    writeInline(node)
                    append("</div>")
                }
            }
        }
    }

    /** Text and CDATA alone in an element stay on its line; everything else gives the element a fold. */
    private fun XmlElement.isLeaf() =
        children.isEmpty() || (children.size == 1 && children[0].let { it is XmlTextNode || it is XmlCdata })

    private fun StringBuilder.writeElement(
        element: XmlElement,
        path: String,
        collapsed: Set<String>,
    ) {
        if (element.isLeaf()) {
            append("<div data-line=\"").append(element.line).append("\">")
            writeStartTag(element, element.closed && element.children.isEmpty())
            element.children.firstOrNull()?.let { writeInline(it) }
            if (element.closed && element.children.isNotEmpty()) writeEndTag(element.name)
            append("</div>")
            return
        }
        append("<details")
        if (path !in collapsed) append(" open")
        append(" data-path=\"")
            .append(HtmlPage.escape(path))
            .append("\" data-line=\"")
            .append(element.line)
            .append("\"><summary>")
        writeStartTag(element, false)
        val size = element.children.size
        append("<span class=\"tail\"> … ")
        // An element left open (by an error or the node limit) has no end tag to show.
        if (element.closed) {
            writeEndTag(element.name)
            append(' ')
        }
        append(size).append(if (size == 1) " child" else " children").append("</span></summary>")
        append("<div class=\"children\">")
        writeNodes(element.children, path, collapsed)
        append("</div>")
        if (element.closed) {
            append("<div class=\"close\">")
            writeEndTag(element.name)
            append("</div>")
        }
        append("</details>")
    }

    /** `<name attr="value">`, or `<name attr="value"/>` if [selfClosing]. */
    private fun StringBuilder.writeStartTag(
        element: XmlElement,
        selfClosing: Boolean,
    ) {
        append("<span class=\"tag\">").append(escape("<" + element.name)).append("</span>")
        for ((name, value) in element.attributes) {
            append(' ')
                .append("<span class=\"attribute\">")
                .append(escape(name))
                .append("</span>")
                .append("<span class=\"tag\">=</span>")
                .append(
                    "<span class=\"string\">",
                ).append(escape("\"" + xmlEscape(value, quotes = true) + "\""))
                .append("</span>")
        }
        append("<span class=\"tag\">").append(if (selfClosing) "/&gt;" else "&gt;").append("</span>")
    }

    private fun StringBuilder.writeEndTag(name: String) {
        append("<span class=\"tag\">").append(escape("</$name>")).append("</span>")
    }

    /** A node that is not an element, on the line of its parent or alone. */
    private fun StringBuilder.writeInline(node: XmlNode) {
        when (node) {
            is XmlTextNode -> {
                append(escape(xmlEscape(node.text, quotes = false)))
            }

            is XmlCdata -> {
                append(
                    "<span class=\"string\">",
                ).append(escape("<![CDATA[${node.text}]]>")).append("</span>")
            }

            is XmlComment -> {
                append("<span class=\"comment\">").append(escape("<!--${node.text}-->")).append("</span>")
            }

            is XmlInstruction -> {
                append(
                    "<span class=\"annotation\">",
                ).append(escape("<?${node.text}?>")).append("</span>")
            }

            is XmlDoctype -> {
                append("<span class=\"annotation\">").append(escape(node.text)).append("</span>")
            }

            is XmlElement -> {
                error("elements are written by writeElement")
            }
        }
    }

    private fun escape(text: String) = HtmlPage.escape(text)
}
