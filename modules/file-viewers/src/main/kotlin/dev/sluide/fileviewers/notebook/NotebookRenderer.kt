package dev.sluide.fileviewers.notebook

import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.markdown.MarkdownRenderer
import dev.sluide.fileviewers.preview.writeHighlighted
import org.jsoup.nodes.Element
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URLDecoder
import java.nio.file.Path
import java.util.Base64

/**
 * Renders a Jupyter notebook (nbformat 4) as a preview page (ADR 0017):
 * - Markdown cells through [MarkdownRenderer] (math and diagrams included), with their `attachment:` images;
 * - code cells highlighted in the notebook's language, with their execution count;
 * - stored outputs: PNG, JPEG and SVG images (as `<img>`), HTML (sanitised like Markdown, e.g. pandas tables), plain
 *   text and streams, and errors with their traceback (ANSI colours removed). Outputs are shown, never run.
 *
 * A file that is no JSON, or JSON of another shape, shows "Not a valid notebook".
 */
internal object NotebookRenderer {
    private val mapper = JsonMapper.builder().build()
    private val ansiCodes = Regex("""\u001B\[[0-9;]*[A-Za-z]""")

    /** Marker path for `attachment:` images, which the sanitiser would drop; see [markdownCell]. */
    private const val ATTACHMENT = ".sluide-attachment/"

    fun render(
        text: String,
        folder: Path,
        dark: Boolean,
    ): String = MarkdownRenderer.page(body(text, folder), dark)

    fun body(
        text: String,
        folder: Path,
    ): Element {
        val body = Element("body").addClass("notebook")
        val problem =
            try {
                val notebook = mapper.readTree(text)
                if (notebook.isObject && notebook.path("cells").isArray) {
                    cells(body, notebook, folder)
                    null
                } else {
                    "no JSON object with a list of cells"
                }
            } catch (e: JacksonException) {
                // Not JSON, or JSON of another shape (e.g. an object where text belongs).
                e.originalMessage
            }
        if (problem != null) {
            body.empty()
            body.appendElement("p").addClass("notebook-error").text("Not a valid notebook: $problem")
        }
        return body
    }

    private fun cells(
        body: Element,
        notebook: JsonNode,
        folder: Path,
    ) {
        val metadata = notebook.path("metadata")
        val language =
            metadata
                .path("language_info")
                .path("name")
                .asString()
                .ifEmpty { metadata.path("kernelspec").path("language").asString() }
                .ifEmpty { "python" }
                .lowercase()
        val highlighter = Highlighters.forLanguage(language)
        for (cell in notebook.path("cells").values()) {
            when (cell.path("cell_type").asString()) {
                "markdown" -> {
                    markdownCell(body, cell, folder)
                }

                "code" -> {
                    codeCell(body, cell, highlighter, folder)
                }

                else -> {
                    body
                        .appendElement("div")
                        .addClass("cell raw")
                        .appendElement("pre")
                        .text(cell.source())
                }
            }
        }
    }

    private fun markdownCell(
        body: Element,
        cell: JsonNode,
        folder: Path,
    ) {
        val div = body.appendElement("div").addClass("cell markdown")
        // Images attached to the cell (`![x](attachment:name.png)`, `<img src="attachment:…">`): the sanitiser drops
        // the `attachment:` scheme, so they get a marker path first and are embedded afterwards.
        val markdown =
            cell
                .source()
                .replace("](attachment:", "]($ATTACHMENT")
                .replace("src=\"attachment:", "src=\"$ATTACHMENT")
        appendAll(div, MarkdownRenderer.body(markdown, folder))
        for (image in div.select("img[src^=$ATTACHMENT]")) {
            val name = URLDecoder.decode(image.attr("src").removePrefix(ATTACHMENT), Charsets.UTF_8)
            val data = cell.path("attachments").path(name)
            val type = data.propertyNames().firstOrNull { it.startsWith("image/") }
            if (type == null) image.removeAttr("src") else image.attr("src", dataUri(type, data.path(type)))
        }
    }

    private fun codeCell(
        body: Element,
        cell: JsonNode,
        highlighter: SyntaxHighlighter?,
        folder: Path,
    ) {
        val div = body.appendElement("div").addClass("cell code")
        val count = cell.path("execution_count").takeIf { it.isNumber }?.asInt()
        div.appendElement("div").addClass("prompt").text("In [${count ?: " "}]:")
        highlighted(div.appendElement("pre").appendElement("code"), cell.source(), highlighter)
        for (output in cell.path("outputs").values()) output(div, output, folder)
    }

    private fun output(
        div: Element,
        output: JsonNode,
        folder: Path,
    ) {
        val out = div.appendElement("div").addClass("output")
        when (output.path("output_type").asString()) {
            "stream" -> {
                out.addClass(output.path("name").asString())
                out.appendElement("pre").text(stripAnsi(output.path("text").joined()))
            }

            "error" -> {
                val traceback = output.path("traceback").values().joinToString("\n") { it.asString() }
                val text =
                    traceback.ifEmpty {
                        "${output.path(
                            "ename",
                        ).asString()}: ${output.path("evalue").asString()}"
                    }
                out.addClass("error").appendElement("pre").text(stripAnsi(text))
            }

            else -> {
                rich(out, output.path("data"), folder)
            }
        }
    }

    /** The richest representation of an `execute_result` or `display_data` that the preview can show. */
    private fun rich(
        out: Element,
        data: JsonNode,
        folder: Path,
    ) {
        val image = listOf("image/png", "image/jpeg").firstOrNull { data.has(it) }
        // Plotting libraries often store HTML that is only scripts, which sanitising leaves empty.
        val html =
            data
                .takeIf {
                    it.has("text/html")
                }?.let { MarkdownRenderer.sanitized(it.path("text/html").joined(), folder) }
        when {
            image != null -> {
                out.appendElement("img").attr("src", dataUri(image, data.path(image)))
            }

            data.has("image/svg+xml") -> {
                val svg = Base64.getEncoder().encodeToString(data.path("image/svg+xml").joined().toByteArray())
                out.appendElement("img").attr("src", "data:image/svg+xml;base64,$svg")
            }

            html != null && (html.text().isNotBlank() || html.selectFirst("img, table") != null) -> {
                appendAll(out, html)
            }

            data.has("text/markdown") -> {
                appendAll(out, MarkdownRenderer.body(data.path("text/markdown").joined(), folder))
            }

            data.has("text/plain") -> {
                out.appendElement("pre").text(stripAnsi(data.path("text/plain").joined()))
            }
        }
    }

    /** [code] as text with the highlighter's token spans, like fenced code in Markdown. */
    private fun highlighted(
        element: Element,
        code: String,
        highlighter: SyntaxHighlighter?,
    ) {
        writeHighlighted(code, highlighter, { element.appendText(it) }) { token, styleClass ->
            element.appendElement("span").addClass(styleClass).text(token)
        }
    }

    /** Moves all of [source]'s nodes into [target], text between and around its elements too. */
    private fun appendAll(
        target: Element,
        source: Element,
    ) = source.childNodes().toList().forEach { target.appendChild(it) }

    /** Base64 image data from a notebook (often split into lines) as a `data:` URI. */
    private fun dataUri(
        type: String,
        base64: JsonNode,
    ) = "data:$type;base64,${base64.joined().filterNot(Char::isWhitespace)}"

    /** A cell's `source`: a string, or a list of lines to join (nbformat allows both). */
    private fun JsonNode.source() = path("source").joined()

    private fun JsonNode.joined(): String = if (isArray) values().joinToString("") { it.asString() } else asString()

    private fun stripAnsi(text: String) = ansiCodes.replace(text, "")
}
