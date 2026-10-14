package dev.sluide.fileviewers

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Block
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist
import java.io.IOException
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * Renders Markdown (CommonMark with GitHub's tables, strikethrough, task lists and autolinks) to the HTML page of the
 * preview (ADR 0016):
 * - every block element carries `data-line`, its first line in the source (0-based), for scroll sync;
 * - fenced code with a known language is highlighted like the code views (`<span class="keyword">`…);
 * - math ([MarkdownMath]) is typeset by KaTeX, bundled in `vendor/katex` and only added to pages that have math;
 * - ` ```mermaid ` fences are drawn by Mermaid (`vendor/mermaid`, only on pages with diagrams), from the source kept in
 *   `data-source`, so that they can be drawn again in the other colour scheme;
 * - the HTML is sanitised: Markdown files may contain HTML, and scripts or event handlers must not run;
 * - local images (relative or `file:`) are embedded as `data:` URIs, since a page loaded from a string may not read
 *   files; links keep their `href` as written and are resolved by `MarkdownFileView` when clicked;
 * - the page brings its stylesheet and the script that `MarkdownFileView` calls.
 */
internal object MarkdownRenderer {
    private val extensions =
        listOf(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            TaskListItemsExtension.create(),
            AutolinkExtension.create(),
            HeadingAnchorExtension.create(),
            MarkdownMath,
        )

    private val parser =
        Parser
            .builder()
            .extensions(extensions)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build()

    private val renderer =
        HtmlRenderer
            .builder()
            .extensions(extensions)
            .attributeProviderFactory { AttributeProvider { node, _, attributes -> sourceLine(node, attributes) } }
            .nodeRendererFactory(::FencedCodeRenderer)
            .build()

    private val safelist =
        Safelist
            .relaxed()
            .addTags("del", "s", "hr", "input", "details", "summary", "kbd", "mark")
            .addAttributes(":all", "data-line", "class", "id", "align")
            .addAttributes("pre", "data-source")
            .addAttributes("input", "type", "checked", "disabled")
            .addAttributes("details", "open")
            .addProtocols("a", "href", "file")
            .addProtocols("img", "src", "file")
            .preserveRelativeLinks(true)

    private val outputSettings = Document.OutputSettings().prettyPrint(false)

    private val stylesheet = resource("preview.css")
    private val script = resource("preview.js")

    /** The preview page of [markdown], a file in [folder]. */
    fun render(
        markdown: String,
        folder: Path,
        dark: Boolean = false,
    ): String = page(body(markdown, folder), dark)

    /** The rendered, sanitised content of [markdown], a file in [folder], with local images embedded. */
    fun body(
        markdown: String,
        folder: Path,
    ): Element = sanitized(renderer.render(parser.parse(markdown)), folder)

    /** [html] from a file in [folder], sanitised like rendered Markdown, with local images embedded. */
    fun sanitized(
        html: String,
        folder: Path,
    ): Element {
        // The base URI only lets jsoup keep relative links; the page itself has no base, so `#part` stays on the page.
        val clean = Jsoup.clean(html, folder.toUri().toString(), safelist, outputSettings)
        val body = Jsoup.parseBodyFragment(clean).apply { outputSettings(outputSettings) }.body()
        body.select("img[src]").forEach { image ->
            embeddedImage(image.attr("src"), folder)?.let { image.attr("src", it) }
        }
        return body
    }

    /**
     * The preview page around [body] (sanitised content): our stylesheet and script, and KaTeX or Mermaid if it has
     * math or diagrams.
     */
    fun page(
        body: Element,
        dark: Boolean,
    ): String =
        buildString {
            append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n")
            append("<style>\n").append(stylesheet).append("</style>\n")
            val math = body.selectFirst(".math") != null
            if (math) append("<style>\n").append(Vendor.katexCss).append("\n</style>\n")
            append("</head>\n")
            val classes = (body.classNames() + if (dark) setOf("dark") else emptySet()).joinToString(" ")
            append(if (classes.isEmpty()) "<body>\n" else "<body class=\"$classes\">\n")
            append(body.html())
            append("\n<script>\n").append(script).append("</script>\n")
            if (math) inlineScript(Vendor.katexJs, "sluide.renderMath();")
            if (body.selectFirst("pre.mermaid") != null) inlineScript(Vendor.mermaidJs, "sluide.renderDiagrams();")
            append("</body>\n</html>\n")
        }

    /** Adds the [library] to the page, then runs [start]. */
    private fun StringBuilder.inlineScript(
        library: String,
        start: String,
    ) {
        append("<script>\n")
            .append(library)
            .append("\n</script>\n<script>")
            .append(start)
            .append("</script>\n")
    }

    /** A local image at [src] (relative to [folder] or `file:`) as a `data:` URI, or `null` to keep [src]. */
    private fun embeddedImage(
        src: String,
        folder: Path,
    ): String? {
        val file = (resolveLink(src, folder) as? LinkTarget.File)?.path ?: return null
        val type = imageTypes[file.extension.lowercase()] ?: return null
        return try {
            if (!file.isRegularFile() || file.fileSize() > MAX_IMAGE_BYTES) return null
            "data:$type;base64," + Base64.getEncoder().encodeToString(file.readBytes())
        } catch (_: IOException) {
            null
        }
    }

    private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

    private fun sourceLine(
        node: Node,
        attributes: MutableMap<String, String>,
    ) {
        if (node !is Block) return
        node.sourceSpans.firstOrNull()?.let { attributes["data-line"] = it.lineIndex.toString() }
    }

    private fun resource(name: String) =
        checkNotNull(MarkdownRenderer::class.java.getResource(name)) { "missing $name" }.readText()

    /** Fenced code, highlighted like the code views when its language is known. */
    private class FencedCodeRenderer(
        private val context: HtmlNodeRendererContext,
    ) : NodeRenderer {
        private val html = context.writer

        override fun getNodeTypes(): Set<Class<out Node>> = setOf(FencedCodeBlock::class.java)

        override fun render(node: Node) {
            val block = node as FencedCodeBlock
            val language = fenceLanguage(block.info.orEmpty())
            val code = block.literal
            if (language == "mermaid") {
                html.line()
                val attributes = mapOf("class" to "mermaid", "data-source" to code)
                html.tag("pre", context.extendAttributes(block, "pre", attributes))
                html.text(code)
                html.tag("/pre")
                html.line()
                return
            }
            if (language == "math") {
                html.line()
                html.tag("div", context.extendAttributes(block, "div", mapOf("class" to "math display")))
                html.text(code.trimEnd())
                html.tag("/div")
                html.line()
                return
            }
            val highlighter = if (language.isEmpty()) null else RSyntaxHighlighter.forLanguage(language)
            html.line()
            html.tag("pre", context.extendAttributes(block, "pre", emptyMap()))
            val codeClass = if (language.isEmpty()) emptyMap() else mapOf("class" to "language-$language")
            html.tag("code", context.extendAttributes(block, "code", codeClass))
            var position = 0
            for (span in highlighter?.highlight(code).orEmpty()) {
                html.text(code.substring(position, span.start))
                html.tag("span", mapOf("class" to span.style.styleClass))
                html.text(code.substring(span.start, span.end))
                html.tag("/span")
                position = span.end
            }
            html.text(code.substring(position))
            html.tag("/code")
            html.tag("/pre")
            html.line()
        }
    }
}
