package dev.sluide.ebooks

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/** Everything a [ChapterWalker] needs from the page being built. */
internal class WalkContext(
    val book: EpubBook,
    val ownStyle: Boolean,
    val flow: PageText,
    /** Zip path of each spine item to its spine index. */
    val spineIndex: Map<String, Int>,
    /** Book CSS collected from the chapters, by source (a linked file or an inline `<style>`), already sanitised. */
    val styles: MutableMap<String, String>,
    /** The ids already in the page. */
    val usedIds: MutableSet<String>,
    val uris: ResourceUris,
)

/**
 * Writes one chapter as sanitised HTML: only known tags and attributes, links rewritten, resources inlined, every
 * text node in a `<span data-o>` (see [PageText]). Everything else is dropped; a tag it does not know is unwrapped.
 * Elements nested deeper than [MAX_DEPTH] are flattened: only their text is kept, without their tags.
 */
internal class ChapterWalker(
    private val context: WalkContext,
    private val index: Int,
    private val chapter: Chapter,
) {
    private val flow = context.flow
    private val dir = EpubPaths.dirOf(chapter.href)
    private val out = StringBuilder()
    private var dropPreNewline = false

    /** Text of the first heading, for a title when the table of contents has none. */
    var firstHeading: String? = null
        private set

    /** The chapter as a `<section id="ch<index>">`. */
    fun write(): String {
        val doc = Jsoup.parse(chapter.xhtml, "", Parser.xmlParser())
        if (context.ownStyle) collectStyles(doc)
        val body = doc.getElementsByTag("body").firstOrNull()
        val sectionId = "ch$index"
        context.usedIds += sectionId
        flow.block()
        flow.anchor(sectionId)
        out.append("<section").attr("id", sectionId)
        if (context.ownStyle) body?.attr("class")?.takeIf { it.isNotBlank() }?.let { out.attr("class", it) }
        out.append('>')
        val roots = if (body != null) body.also(::registerId).childNodes() else doc.childNodes()
        roots.forEach { node(it, 0) }
        out.append("</section>")
        flow.block()
        flow.resolveAnchorsAtEnd()
        return out.toString()
    }

    private fun collectStyles(doc: org.jsoup.nodes.Document) {
        for (link in doc.getElementsByTag("link")) {
            if (!link.attr("rel").split(' ').any { it.equals("stylesheet", ignoreCase = true) }) continue
            val path = EpubPaths.resolve(dir, link.attr("href")).path
            val resource = context.book.resources[path]?.takeIf { it.mediaType == "text/css" } ?: continue
            context.styles.getOrPut("link:$path") {
                CssSanitizer.sanitize(EpubXml.decode(resource.bytes), EpubPaths.dirOf(path), context.uris)
            }
        }
        doc.getElementsByTag("style").forEachIndexed { n, style ->
            context.styles.getOrPut("style:$index:$n") { CssSanitizer.sanitize(style.wholeText(), dir, context.uris) }
        }
    }

    private fun node(
        node: Node,
        depth: Int,
    ) {
        when (node) {
            is Element -> element(node, depth)
            is TextNode -> text(node.wholeText)
            else -> Unit
        }
    }

    private fun text(raw: String) {
        var content = raw
        if (dropPreNewline) {
            dropPreNewline = false
            content = content.removePrefix("\r\n").removePrefix("\n")
        }
        flow.text(content)?.let {
            out
                .append(
                    "<span",
                ).attr("data-o", it.offset.toString())
                .append('>')
                .append(escapeText(it.content))
                .append("</span>")
        }
    }

    private fun element(
        el: Element,
        depth: Int,
    ) {
        dropPreNewline = false
        val tag = el.normalName()
        if (tag in HtmlTags.dropped) return
        if (depth >= MAX_DEPTH) return flatten(el)
        when (tag) {
            "img" -> {
                image(el)
            }

            "svg" -> {
                svg(el)
            }

            "br" -> {
                flow.lineBreak()
                out.append("<br>")
                // An unclosed br in XML holds what follows it.
                el.childNodes().forEach { node(it, depth + 1) }
            }

            "hr" -> {
                registerId(el)
                flow.block()
                out.append("<hr>")
                flow.block()
                el.childNodes().forEach { node(it, depth + 1) }
            }

            else -> {
                general(el, tag, depth)
            }
        }
    }

    /** The text of a too deeply nested element, in document order, with its line and block breaks but not its tags. */
    private fun flatten(root: Element) {
        val stack = ArrayDeque<Any>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            when (val current = stack.removeLast()) {
                is TextNode -> {
                    text(current.wholeText)
                }

                BlockEnd -> {
                    flow.block()
                }

                is Element -> {
                    val tag = current.normalName()
                    if (tag in HtmlTags.dropped) continue
                    if (tag == "br") flow.lineBreak()
                    if (tag in HtmlTags.blocks) {
                        flow.block()
                        stack.addLast(BlockEnd)
                    }
                    current.childNodes().asReversed().forEach(stack::addLast)
                }
            }
        }
    }

    private object BlockEnd

    private fun general(
        el: Element,
        tag: String,
        depth: Int,
    ) {
        val id = registerId(el)
        val attributes = StringBuilder()
        var emit: String? = if (tag in HtmlTags.allowed) tag else null
        if (tag == "a") {
            val target = linkTarget(el.attr("href"))
            if (target != null) attributes.attr("href", target) else emit = if (id != null) "span" else null
        } else if (emit == null && id != null) {
            emit = "span"
        }
        if (id != null) attributes.attr("id", id)
        copyAttributes(el, tag, attributes)
        if (tag in HtmlTags.headings && firstHeading == null) firstHeading = el.text().trim().ifEmpty { null }

        val block = tag in HtmlTags.blocks
        val skip = isSkipped(el, tag)
        if (block) flow.block()
        if (skip) flow.beginSkip()
        if (tag == "pre") {
            flow.preDepth++
            dropPreNewline = true
        }
        if (emit != null) out.append("<$emit$attributes>")
        if (emit != null && tag in tableParents) {
            el.childNodes().forEach { tableChild(it, tag, depth + 1) }
        } else {
            el.childNodes().forEach { node(it, depth + 1) }
        }
        if (emit != null) out.append("</$emit>")
        if (tag == "pre") flow.preDepth--
        if (skip) flow.endSkip()
        if (block) flow.block()
    }

    /**
     * A child of a table part. Text or other markup that is not part of the table's structure is moved before the
     * table by HTML parsers (foster parenting), which would break the order of the offsets; it gets a cell of its
     * own at its position, so that nothing moves.
     */
    private fun tableChild(
        child: Node,
        parent: String,
        depth: Int,
    ) {
        val stray =
            when (child) {
                is TextNode -> {
                    makesSpan(child.wholeText)
                }

                is Element -> {
                    child.normalName().let { it !in tableStructure && it !in HtmlTags.dropped } &&
                        makesSpan(child.wholeText())
                }

                else -> {
                    false
                }
            }
        if (!stray) return node(child, depth)
        val cell = if (parent == "tr") "<td>" else "<tr><td>"
        out.append(cell)
        node(child, depth)
        out.append(if (parent == "tr") "</td>" else "</td></tr>")
    }

    /**
     * Whether [text] would get a `data-o` span, which is the parser's rule for text that is moved out of a table: any
     * character but the five ASCII whitespace characters (not `&nbsp;`), or anything at all inside `pre`.
     */
    private fun makesSpan(text: String) = text.isNotEmpty() && (flow.preDepth > 0 || text.any { it !in HTML_SPACE })

    private fun copyAttributes(
        el: Element,
        tag: String,
        into: StringBuilder,
    ) {
        val lang = el.attr("lang").ifBlank { el.attr("xml:lang") }
        if (lang.isNotBlank()) into.attr("lang", lang)
        el
            .attr("dir")
            .lowercase()
            .takeIf { it == "ltr" || it == "rtl" || it == "auto" }
            ?.let { into.attr("dir", it) }
        when (tag) {
            "ol" -> {
                el.attr("start").toIntOrNull()?.let { into.attr("start", it.toString()) }
            }

            "td", "th" -> {
                el.attr("colspan").toIntOrNull()?.let { into.attr("colspan", it.toString()) }
                el.attr("rowspan").toIntOrNull()?.let { into.attr("rowspan", it.toString()) }
            }
        }
        if (context.ownStyle) {
            el.attr("class").takeIf { it.isNotBlank() }?.let { into.attr("class", it) }
            el.attr("style").takeIf { it.isNotBlank() }?.let {
                val clean = CssSanitizer.sanitize(it, dir, context.uris)
                if (clean.isNotBlank()) into.attr("style", clean)
            }
        }
    }

    /** The unique id this element gets in the page (`ch<i>-<id>`), which its anchor will point at; null if none. */
    private fun registerId(el: Element): String? {
        val raw = el.attr("id").ifBlank { if (el.normalName() == "a") el.attr("name") else "" }
        if (raw.isBlank() || raw.any { it.isWhitespace() }) return null
        val id = "ch$index-$raw"
        if (!context.usedIds.add(id)) return null
        flow.anchor(id)
        return id
    }

    /** The `href` for a link as written, or null when the link has to go (its text stays). */
    private fun linkTarget(href: String): String? {
        if (href.isBlank()) return null
        val scheme = EpubPaths.schemeOf(href)
        if (scheme != null) {
            return if (scheme == "http" || scheme == "https" || scheme == "mailto") href.trim() else null
        }
        if (href.trim().startsWith("#")) {
            val fragment = EpubPaths.decode(href.trim().substring(1))
            return if (fragment.isEmpty()) "#ch$index" else "#ch$index-$fragment"
        }
        if (href.trim().startsWith("//")) return null
        val ref = EpubPaths.resolve(dir, href)
        val target = context.spineIndex[ref.path] ?: return null
        return if (ref.fragment.isNullOrEmpty()) "#ch$target" else "#ch$target-${ref.fragment}"
    }

    private fun imageData(href: String): String? =
        context.uris.dataUri(href, dir)?.takeIf { it.startsWith("data:image/") }

    private fun image(el: Element) {
        registerId(el)
        val data = imageData(el.attr("src").ifBlank { el.attr("xlink:href") }) ?: return
        out.append("<img")
        if (context.uris.inline) out.attr("src", data)
        out.attr("alt", el.attr("alt")).append('>')
    }

    /** Inline SVG is kept only as far as its `image` elements (cover pages); the rest of it is not text of the book. */
    private fun svg(el: Element) {
        val images =
            el.getAllElements().filter { it.normalName() == "image" }.mapNotNull { image ->
                imageData(image.attr("xlink:href").ifBlank { image.attr("href") })?.let { image to it }
            }
        if (images.isEmpty()) return
        out.append("<svg")
        for (name in listOf("viewBox", "width", "height", "preserveAspectRatio")) {
            el.attr(name).takeIf { it.isNotBlank() }?.let { out.attr(name, it) }
        }
        out.append('>')
        for ((image, data) in images) {
            out.append("<image")
            if (context.uris.inline) out.attr("href", data)
            for (name in listOf("x", "y", "width", "height", "preserveAspectRatio")) {
                image.attr(name).takeIf { it.isNotBlank() }?.let { out.attr(name, it) }
            }
            out.append("></image>")
        }
        out.append("</svg>")
    }

    /** Text that is not read aloud: note references, tables, page breaks. */
    private fun isSkipped(
        el: Element,
        tag: String,
    ): Boolean {
        val types = el.attr("epub:type").split(' ').filter { it.isNotEmpty() }
        val role = el.attr("role")
        if ("pagebreak" in types || role == "doc-pagebreak") return true
        return when (tag) {
            "table" -> true
            "a" -> "noteref" in types || role == "doc-noteref" || el.parent()?.normalName() == "sup"
            "sup" -> numberMark.matches(el.text().trim()) && el.selectFirst("a[href]") != null
            else -> false
        }
    }

    companion object {
        /** Deeper elements are flattened to their text; previews' pages cap at the same order of magnitude. */
        const val MAX_DEPTH = 200

        private const val HTML_SPACE = " \t\n\u000C\r"
        private val tableParents = setOf("table", "thead", "tbody", "tfoot", "tr")
        private val tableStructure = setOf("caption", "colgroup", "col", "thead", "tbody", "tfoot", "tr", "td", "th")

        private val numberMark = Regex("\\[?\\d+]?")
    }
}
