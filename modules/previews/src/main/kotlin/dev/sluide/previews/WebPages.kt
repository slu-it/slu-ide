package dev.sluide.previews

import javafx.scene.web.WebEngine
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode

/**
 * Loads [html] like [WebEngine.loadContent], with every character outside the BMP (emoji) written so that it survives
 * the way: `loadContent` hands the string to WebKit through a JNI modified-UTF-8 conversion that encodes each surrogate
 * as three bytes, so WebKit shows six U+FFFD for one emoji (JDK-8304041, ADR 0190). Every page of Thoreau goes through
 * here, not through `loadContent`; call it on the FX thread like `loadContent`.
 *
 * Known limits: astral characters inside CDATA sections and inside other raw-text elements (`xmp`, `noembed`,
 * `noframes`, `iframe` content) are not repaired; see [escapeSupplementary].
 */
fun WebEngine.loadPage(html: String) = loadContent(escapeSupplementary(html))

/**
 * [html] without surrogate chars, meaning the same: text and attribute values get numeric references (jsoup writes
 * them for the US-ASCII charset), `<script>` content the surrogates as `\uD83D\uDE80` and `<style>` content a CSS
 * escape `\1F680 `. A page without a surrogate is returned as is (the same instance), so it loads as before.
 */
internal fun escapeSupplementary(html: String): String {
    if (html.none { it.isSurrogate() }) return html
    val document = Jsoup.parse(html)
    document.outputSettings().prettyPrint(false).charset(Charsets.US_ASCII)
    escapeDataNodes(document)
    restorePreNewlines(document)
    return document.outerHtml()
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
