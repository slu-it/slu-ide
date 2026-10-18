package dev.sluide.fileviewers.markdown

import dev.sluide.fileviewers.preview.FileExport
import dev.sluide.fileviewers.preview.writeReplacing
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * An exported [html] page; [problems] are lines for the user about what it lacks (an image that could not be embedded,
 * a diagram that could not be drawn).
 */
internal class ExportedPage(
    val html: String,
    val problems: List<String>,
)

/**
 * Markdown as a self-contained HTML page `name.html` beside `name.md`: images (local and, via [images], remote) are
 * embedded, math is typeset and diagrams are drawn by [renderer] (each in a light and a dark variant), and a
 * `name.css` beside the file is inlined after the built-in styles. The page follows the reader's colour scheme and
 * has no script but the one that does so.
 */
internal class MarkdownHtmlExport(
    private val images: ImageFetcher = HttpImageFetcher(),
    private val renderer: ExportRenderer = WebViewExportRenderer(),
) : FileExport {
    override val label get() = "HTML"

    override fun target(file: Path): Path = file.resolveSibling(file.nameWithoutExtension + ".html")

    /** The stylesheet that [file]'s export includes if it exists. */
    fun stylesheet(file: Path): Path = file.resolveSibling(file.nameWithoutExtension + ".css")

    override fun write(
        file: Path,
        text: String,
        target: Path,
    ): List<String> {
        val page = render(file, text)
        writeReplacing(target) { Files.writeString(it, page.html) }
        return page.problems
    }

    /** The page for [text], the saved content of [file]; blocking (may download images), never on the FX thread. */
    fun render(
        file: Path,
        text: String,
    ): ExportedPage {
        val body = MarkdownRenderer.body(text, file.parent)
        // Each address is downloaded once, failures included.
        val downloaded = mutableMapOf<URI, String?>()
        body.select("img[src]").forEach { image ->
            val uri = remote(image.attr("src")) ?: return@forEach
            if (uri !in downloaded) {
                downloaded[uri] =
                    images.fetch(uri)?.let { "data:${it.type};base64," + Base64.getEncoder().encodeToString(it.bytes) }
            }
            downloaded[uri]?.let { image.attr("src", it) }
        }
        val notEmbedded =
            body
                .select("img[src]")
                .map { it.attr("src") }
                .filterNot { it.startsWith("data:") }
                .distinct()
        val failed = rendered(body)
        val css = stylesheet(file).takeIf { it.isRegularFile() }?.readText()?.replace(STYLE_END, "<\\\\/$1")
        val html =
            MarkdownRenderer.page(
                body,
                dark = false,
                standalone = true,
                title = file.fileName.toString(),
                extraStyles = listOfNotNull(css),
            )
        return ExportedPage(html, notEmbedded.map { "Image not embedded: $it" } + failed)
    }

    /**
     * Replaces the math in [body] by its typeset HTML and the diagrams by their drawings, both as the renderer gave
     * them (KaTeX's output and Mermaid's sanitised SVG, as the preview inserts them). A formula that was not typeset
     * keeps its source, a diagram that could not be drawn shows its error. Returns the problems for the user.
     */
    private fun rendered(body: Element): List<String> {
        val formulas = body.select(".math")
        val diagrams = body.select("pre.mermaid")
        val rendering =
            renderer.render(
                formulas.map { MathSource(it.wholeText(), it.hasClass("display")) },
                diagrams.map { it.attr("data-source") },
            )
        var untypeset = false
        formulas.zip(rendering.math).forEach { (formula, html) ->
            if (html == null) {
                untypeset = true
                return@forEach
            }
            // The same tag: inline display math is a span, so that it stays in its paragraph.
            val typeset = Element(formula.tagName()).addClass("math-rendered").appendChild(DataNode(html))
            if (formula.hasClass("display")) typeset.addClass("display")
            formula.replaceWith(typeset)
        }
        val failed =
            diagrams.zip(rendering.diagrams).mapIndexedNotNull { i, (diagram, drawn) ->
                val (light, dark) = drawn.light to drawn.dark
                if (drawn.error == null && light != null && dark != null) {
                    val replacement = Element("div").addClass("diagram")
                    replacement.appendElement("div").addClass("diagram-light").appendChild(DataNode(light))
                    replacement.appendElement("div").addClass("diagram-dark").appendChild(DataNode(dark))
                    diagram.replaceWith(replacement)
                    null
                } else {
                    val error = drawn.error ?: "Not drawn"
                    val shown = Element("pre").addClass("mermaid").addClass("error").text(error)
                    // Not Mermaid's error, so no syntax error.
                    if (drawn.failed) shown.addClass("failed")
                    diagram.replaceWith(shown)
                    "Diagram ${i + 1} could not be drawn: $error"
                }
            }
        return (if (untypeset) listOf("Math could not be typeset") else emptyList()) + failed
    }

    private companion object {
        /** A `</style` in the stylesheet would end its element. */
        val STYLE_END = Regex("</(style)", RegexOption.IGNORE_CASE)
    }

    private fun remote(src: String): URI? =
        try {
            URI(src.trim()).takeIf { it.scheme?.lowercase() in setOf("http", "https") }
        } catch (_: URISyntaxException) {
            null
        }
}
