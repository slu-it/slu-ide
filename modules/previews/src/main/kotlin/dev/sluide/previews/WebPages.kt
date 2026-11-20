package dev.sluide.previews

import javafx.scene.web.WebEngine
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.text.BreakIterator
import java.util.Locale

/**
 * Loads [html] like [WebEngine.loadContent], with every character outside the BMP (emoji) written so that it survives
 * the way: `loadContent` hands the string to WebKit through a JNI modified-UTF-8 conversion that encodes each surrogate
 * as three bytes, so WebKit shows six U+FFFD for one emoji (JDK-8304041, ADR 0190). Each emoji also gets a span of its
 * own, which JavaFX's WebKit on macOS needs to lay out the text around it. Every page of Thoreau goes through here, not
 * through `loadContent`; call it on the FX thread like `loadContent`.
 *
 * Known limits: emoji that scripts write after the load, and in `textarea`, `title`, `option`, `svg` and `math`; astral
 * characters inside CDATA sections and inside other raw-text elements (`xmp`, `noembed`, `noframes`, `iframe` content,
 * `plaintext`) are not repaired; see [preparePage].
 */
fun WebEngine.loadPage(html: String) = loadContent(preparePage(html))

/**
 * [html] with each emoji cluster of a text in its own `<span data-emoji="">` and without surrogate chars, meaning the
 * same: text and attribute values get numeric references (jsoup writes them for the US-ASCII charset), `<script>`
 * content the surrogates as `\uD83D\uDE80` and `<style>` content a CSS escape `\1F680 `. A page without a surrogate or
 * other emoji char is returned as is (the same instance), so it loads as before.
 */
internal fun preparePage(html: String): String {
    if (!mayHoldEmoji(html)) return html
    val document = Jsoup.parse(html)
    document.outputSettings().prettyPrint(false).charset(Charsets.US_ASCII)
    wrapEmoji(document)
    escapeDataNodes(document)
    restorePreNewlines(document)
    return document.outerHtml()
}

/** Whether [text] has a surrogate or a char that can be part of an emoji cluster: a pictograph, FE0F or the keycap. */
private fun mayHoldEmoji(text: String) =
    text.any {
        it.isSurrogate() || it == '\uFE0F' || it == '\u20E3' || Character.isExtendedPictographic(it.code)
    }

private fun isEmoji(cluster: String) =
    cluster.codePoints().anyMatch {
        Character.isExtendedPictographic(it) || it in 0x1F1E6..0x1F1FF || it == 0xFE0F || it == 0x20E3
    }

private fun graphemeClusters(text: String): List<String> {
    val breaks = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    val clusters = mutableListOf<String>()
    var start = breaks.first()
    var end = breaks.next()
    while (end != BreakIterator.DONE) {
        clusters += text.substring(start, end)
        start = end
        end = breaks.next()
    }
    return clusters
}

private val NO_SPAN_INSIDE = setOf("textarea", "title", "option", "svg", "math")

/** Whether [text] is the one emoji of a span of ours, which stays as it is when a page is prepared twice. */
private fun isOwnSpanText(text: TextNode) =
    text.parent()?.let { it.normalName() == "span" && it.hasAttr("data-emoji") } == true &&
        graphemeClusters(text.wholeText).singleOrNull()?.let(::isEmoji) == true

/** Whether a span may stand in [text]'s place: not in text-only or foreign content, not in a span of ours. */
private fun canHoldSpan(text: TextNode) =
    generateSequence(text.parent()) { it.parent() }.none { it.normalName() in NO_SPAN_INSIDE } && !isOwnSpanText(text)

/** Wraps each emoji cluster of every text node in its own span: see [preparePage]. */
internal fun wrapEmoji(document: Document) {
    val texts = mutableListOf<TextNode>()
    NodeTraversor.traverse(
        NodeVisitor { node, _ ->
            if (node is TextNode && mayHoldEmoji(node.wholeText) &&
                canHoldSpan(node)
            ) {
                texts += node
            }
        },
        document,
    )
    for (text in texts) {
        val pieces = mutableListOf<Node>()
        val plain = StringBuilder()

        fun endPlainRun() {
            if (plain.isNotEmpty()) pieces.add(TextNode(plain.toString()))
            plain.setLength(0)
        }
        for (cluster in graphemeClusters(text.wholeText)) {
            if (isEmoji(cluster)) {
                endPlainRun()
                pieces.add(Element("span").attr("data-emoji", "").appendText(cluster))
            } else {
                plain.append(cluster)
            }
        }
        endPlainRun()
        pieces.forEach(text::before)
        text.remove()
    }
}

private fun escapeDataNodes(document: Document) {
    for (data in document.select("script").flatMap { it.dataNodes() }) {
        data.wholeData = rewriteAstral(data.wholeData) { "\\u%04X\\u%04X".format(it.high, it.low) }
    }
    for (data in document.select("style").flatMap { it.dataNodes() }) {
        data.wholeData = rewriteAstral(data.wholeData) { "\\%X ".format(it.codePoint) }
    }
}

private class Astral(
    val codePoint: Int,
) {
    val high = Character.highSurrogate(codePoint).code
    val low = Character.lowSurrogate(codePoint).code
}

private fun rewriteAstral(
    text: String,
    replacement: (Astral) -> String,
): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (Character.isSupplementaryCodePoint(cp)) out.append(replacement(Astral(cp))) else out.appendCodePoint(cp)
        i += Character.charCount(cp)
    }
    return out.toString()
}

/** jsoup's parser drops the first newline after `<pre>` and `<listing>`; its writer does not add it back. */
private fun restorePreNewlines(document: Document) {
    for (element in document.select("pre, listing")) {
        val first = element.childNodes().firstOrNull()
        if (first is TextNode && first.wholeText.startsWith("\n")) first.text("\n" + first.wholeText)
    }
}
