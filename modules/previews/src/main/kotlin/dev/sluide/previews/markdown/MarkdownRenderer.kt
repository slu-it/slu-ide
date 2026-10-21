package dev.sluide.previews.markdown

import dev.sluide.fileviewers.Highlighters
import dev.sluide.previews.image.imageTypes
import dev.sluide.previews.preview.HtmlPage
import dev.sluide.previews.preview.LinkTarget
import dev.sluide.previews.preview.Vendor
import dev.sluide.previews.preview.resolveLink
import dev.sluide.previews.preview.writeHighlighted
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
 *   files; links keep their `href` as written and are resolved by `PreviewFileView` when clicked;
 * - the page brings its stylesheet and the script that `PreviewFileView` calls.
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

    private val stylesheet = HtmlPage.resource("preview.css")
    private val script = HtmlPage.resource("preview.js")

    /** The preview page of [markdown], a file in [folder]. */
    fun render(
        markdown: String,
        folder: Path,
        dark: Boolean = false,
    ): String = page(body(markdown, folder), dark)

    /** The syntax tree of [markdown], with source spans on its blocks; see [MarkdownOutline]. */
    internal fun parse(markdown: String): Node = parser.parse(markdown)

    /** The rendered, sanitised content of [markdown], a file in [folder], with local images embedded. */
    fun body(
        markdown: String,
        folder: Path,
    ): Element = sanitized(renderer.render(parse(markdown)), folder)

    /** [html] from a file in [folder], sanitised like rendered Markdown, with local images embedded. */
    fun sanitized(
        html: String,
        folder: Path,
    ): Element {
        // The base URI only lets jsoup keep relative links; the page itself has no base, so `#part` stays on the page.
        val clean = Jsoup.clean(html, folder.toUri().toString(), safelist, outputSettings)
        val body = Jsoup.parseBodyFragment(clean).apply { outputSettings(outputSettings) }.body()
        // `input` is allowed for task-list checkboxes; any other input would be a working control.
        body.select("input:not([type=checkbox])").remove()
        body.select("img[src]").forEach { image ->
            embeddedImage(image.attr("src"), folder)?.let { image.attr("src", it) }
        }
        return body
    }

    /**
     * The preview page around [body] (sanitised content): our stylesheet and script, and KaTeX or Mermaid if it has
     * math or diagrams. A [standalone] page is for a browser, with its math typeset and its diagrams drawn already
     * ([MarkdownHtmlExport]): its only script follows the reader's colour scheme and adds a button to switch it
     * ([dark] is ignored), and it has KaTeX's stylesheet only if it has typeset math (`.math-rendered`). It has a
     * [title] and [extraStyles] after all other styles.
     */
    fun page(
        body: Element,
        dark: Boolean,
        standalone: Boolean = false,
        title: String? = null,
        extraStyles: List<String> = emptyList(),
    ): String {
        val classes = (if (standalone) listOf("standalone") else emptyList()) + body.classNames()
        if (standalone) {
            val katex = if (body.selectFirst(".math-rendered") != null) listOf(Vendor.katexCss) else emptyList()
            val styles = listOf(stylesheet) + katex + EXPORT_STYLESHEET + extraStyles
            return HtmlPage.render(false, styles, listOf(THEME_SCRIPT), classes, title) { append(body.html()) }
        }
        val math = body.selectFirst(".math") != null
        val diagrams = body.selectFirst("pre.mermaid") != null
        // Each library in a script of its own, then the call that starts it.
        val scripts =
            listOf(script) +
                (if (math) listOf(Vendor.katexJs, "sluide.renderMath();") else emptyList()) +
                (if (diagrams) listOf(Vendor.mermaidJs, "sluide.renderDiagrams();") else emptyList())
        val styles = (if (math) listOf(stylesheet, Vendor.katexCss) else listOf(stylesheet)) + extraStyles
        return HtmlPage.render(dark, styles, scripts, classes, title) { append(body.html()) }
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

    /** The largest image that is embedded in a page. */
    internal const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

    // Follows the reader's colour scheme and adds a button to switch it for as long as the page is open (nothing is
    // stored); a later change of the system scheme applies again. Created here so that a page without JavaScript has
    // no dead control.
    private const val THEME_SCRIPT =
        """
(function () {
    var dark = function () { return document.body.classList.contains('dark'); };
    // A copy of the page saved by a browser already has the button.
    var button = document.querySelector('.theme-toggle');
    var existing = !!button;
    if (!existing) {
        button = document.createElement('button');
        button.type = 'button';
        button.className = 'theme-toggle';
    }
    var sync = function () {
        var label = dark() ? 'Switch to light theme' : 'Switch to dark theme';
        button.setAttribute('aria-label', label);
        button.title = label;
        button.textContent = dark() ? '\u2600' : '\u263E';
    };
    button.addEventListener('click', function () {
        document.body.classList.toggle('dark');
        sync();
    });
    if (!existing) document.body.appendChild(button);
    sync();
    var query = window.matchMedia && matchMedia('(prefers-color-scheme: dark)');
    if (query) {
        var follow = function () {
            document.body.classList.toggle('dark', query.matches);
            sync();
        };
        follow();
        if (query.addEventListener) query.addEventListener('change', follow);
        else if (query.addListener) query.addListener(follow);
    }
})();
"""

    // A drawn diagram in the variant of the colour scheme, laid out like pre.mermaid in preview.css; display math like
    // .math.display; a diagram that failed for another reason than its syntax without the label "Syntax error"; the
    // colour scheme button in the palette of preview.css (the reader's own stylesheet can restyle or hide it).
    private const val EXPORT_STYLESHEET =
        """
.math-rendered.display {
    display: block;
    margin: 0 0 16px 0;
    overflow-x: auto;
    overflow-y: hidden;
}

pre.mermaid.error.failed::before {
    display: none;
}

body.dark .diagram-light,
body:not(.dark) .diagram-dark {
    display: none;
}

.diagram {
    margin: 0 0 16px 0;
    padding: 16px;
    overflow: auto;
    text-align: center;
}

.diagram svg {
    max-width: 100%;
    height: auto;
}

body.standalone {
    padding-right: 56px;
}

.theme-toggle {
    position: fixed;
    top: 12px;
    right: 12px;
    width: 32px;
    height: 32px;
    padding: 0;
    border: 1px solid #d1d9e0;
    border-radius: 50%;
    background: #ffffff;
    color: #1f2328;
    font-size: 16px;
    line-height: 30px;
    cursor: pointer;
}

body.dark .theme-toggle {
    background: #2b2b2b;
    border-color: #3d444d;
    color: #d1d7e0;
}

.theme-toggle:focus-visible {
    outline: 2px solid #4493f8;
    outline-offset: 2px;
}

@media print {
    .theme-toggle {
        display: none;
    }
}
"""

    private fun sourceLine(
        node: Node,
        attributes: MutableMap<String, String>,
    ) {
        if (node !is Block) return
        node.sourceSpans.firstOrNull()?.let { attributes["data-line"] = it.lineIndex.toString() }
    }

    /** Fenced code, highlighted like the code views when its language is known. */
    private class FencedCodeRenderer(
        private val context: HtmlNodeRendererContext,
    ) : NodeRenderer {
        private val html = context.writer

        override fun getNodeTypes(): Set<Class<out Node>> = setOf(FencedCodeBlock::class.java)

        override fun render(node: Node) {
            val block = node as FencedCodeBlock
            val language = Highlighters.fenceLanguage(block.info.orEmpty())
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
            val highlighter = if (language.isEmpty()) null else Highlighters.forLanguage(language)
            html.line()
            html.tag("pre", context.extendAttributes(block, "pre", emptyMap()))
            val codeClass = if (language.isEmpty()) emptyMap() else mapOf("class" to "language-$language")
            html.tag("code", context.extendAttributes(block, "code", codeClass))
            writeHighlighted(code, highlighter, html::text) { token, styleClass ->
                html.tag("span", mapOf("class" to styleClass))
                html.text(token)
                html.tag("/span")
            }
            html.tag("/code")
            html.tag("/pre")
            html.line()
        }
    }
}
