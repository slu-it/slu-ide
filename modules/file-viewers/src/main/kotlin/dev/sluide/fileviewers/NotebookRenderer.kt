package dev.sluide.fileviewers

import org.jsoup.nodes.Element
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.util.Base64

/**
 * Renders a Jupyter notebook (nbformat 4) as a preview page (ADR 0017):
 * - Markdown cells through [MarkdownRenderer] (math and diagrams included), with their `attachment:` images;
 * - code cells highlighted in the notebook's language, with their execution count;
 * - stored outputs: PNG, JPEG and SVG images (as `<img>`), HTML (sanitised like Markdown, e.g. pandas tables), plain
 *   text and streams, and errors with their traceback (ANSI colours removed). Outputs are shown, never run.
 */
internal object NotebookRenderer {
    private val mapper = JsonMapper.builder().build()
    private val ansiCodes = Regex("""\u001B\[[0-9;]*[A-Za-z]""")

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
        val notebook =
            try {
                mapper.readTree(text)
            } catch (e: JacksonException) {
                body.appendElement("p").addClass("notebook-error").text("Not a valid notebook: ${e.originalMessage}")
                return body
            }
        val metadata = notebook.path("metadata")
        val language =
            metadata
                .path("language_info")
                .path("name")
                .asString()
                .ifEmpty {
                    metadata.path("kernelspec").path("language").asString()
                }.ifEmpty { "python" }
                .lowercase()
        val highlighter = RSyntaxHighlighter.forLanguage(language)
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
        return body
    }

    private fun markdownCell(
        body: Element,
        cell: JsonNode,
        folder: Path,
    ) {
        val div = body.appendElement("div").addClass("cell markdown")
        // Images attached to the cell (`![x](attachment:name.png)`): the sanitiser drops the `attachment:` scheme, so
        // they get a marker path first and are embedded afterwards.
        val markdown = cell.source().replace("(attachment:", "($ATTACHMENT")
        MarkdownRenderer
            .body(markdown, folder)
            .children()
            .toList()
            .forEach { div.appendChild(it) }
        for (image in div.select("img[src^=$ATTACHMENT]")) {
            val data = cell.path("attachments").path(image.attr("src").removePrefix(ATTACHMENT))
            val type = data.propertyNames().firstOrNull { it.startsWith("image/") }
            if (type == null) {
                image.removeAttr("src")
            } else {
                image.attr("src", "data:$type;base64,${data.path(type).joined().filterNot(Char::isWhitespace)}")
            }
        }
    }

    private const val ATTACHMENT = ".sluide-attachment/"

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
                out
                    .addClass(
                        output.path("name").asString(),
                    ).appendElement("pre")
                    .text(stripAnsi(output.path("text").joined()))
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
        when {
            image != null -> {
                out
                    .appendElement(
                        "img",
                    ).attr("src", "data:$image;base64,${data.path(image).joined().filterNot(Char::isWhitespace)}")
            }

            data.has("image/svg+xml") -> {
                val svg =
                    Base64.getEncoder().encodeToString(
                        data.path("image/svg+xml").joined().toByteArray(),
                    )
                out.appendElement("img").attr("src", "data:image/svg+xml;base64,$svg")
            }

            data.has("text/html") -> {
                MarkdownRenderer
                    .sanitized(
                        data.path("text/html").joined(),
                        folder,
                    ).children()
                    .toList()
                    .forEach { out.appendChild(it) }
            }

            data.has("text/markdown") -> {
                MarkdownRenderer
                    .body(
                        data.path("text/markdown").joined(),
                        folder,
                    ).children()
                    .toList()
                    .forEach { out.appendChild(it) }
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
        var position = 0
        for (span in highlighter?.highlight(code).orEmpty()) {
            element.appendText(code.substring(position, span.start))
            element.appendElement("span").addClass(span.style.styleClass).text(code.substring(span.start, span.end))
            position = span.end
        }
        element.appendText(code.substring(position))
    }

    /** A cell's `source`: a string, or a list of lines to join (nbformat allows both). */
    private fun JsonNode.source() = path("source").joined()

    private fun JsonNode.joined(): String = if (isArray) values().joinToString("") { it.asString() } else asString()

    private fun stripAnsi(text: String) = ansiCodes.replace(text, "")
}
