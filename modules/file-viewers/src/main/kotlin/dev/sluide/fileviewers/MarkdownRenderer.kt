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
import org.jsoup.safety.Safelist
import java.nio.file.Path

/**
 * Renders Markdown (CommonMark with GitHub's tables, strikethrough, task lists and autolinks) to the HTML page of the
 * preview (ADR 0016):
 * - every block element carries `data-line`, its first line in the source (0-based), for scroll sync;
 * - fenced code with a known language is highlighted like the code views (`<span class="keyword">`…);
 * - the HTML is sanitised: Markdown files may contain HTML, and scripts or event handlers must not run;
 * - relative links and images resolve against the Markdown file's folder (`<base href>`);
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
            .addAttributes("input", "type", "checked", "disabled")
            .addAttributes("details", "open")
            .addProtocols("a", "href", "file")
            .addProtocols("img", "src", "file", "data")
            .preserveRelativeLinks(true)

    private val outputSettings = Document.OutputSettings().prettyPrint(false)

    private val stylesheet = resource("markdown-preview.css")
    private val script = resource("markdown-preview.js")

    /** The preview page of [markdown], a file in [folder]. */
    fun render(
        markdown: String,
        folder: Path,
    ): String {
        val base = folder.toUri().toString().let { if (it.endsWith("/")) it else "$it/" }
        val body = Jsoup.clean(renderer.render(parser.parse(markdown)), base, safelist, outputSettings)
        return buildString {
            append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n")
            append("<base href=\"").append(base.replace("\"", "%22")).append("\">\n")
            append("<style>\n").append(stylesheet).append("</style>\n</head>\n<body>\n")
            append(body)
            append("\n<script>\n").append(script).append("</script>\n</body>\n</html>\n")
        }
    }

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
            val highlighter = if (language.isEmpty()) null else RSyntaxHighlighter.forLanguage(language)
            val code = block.literal
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
