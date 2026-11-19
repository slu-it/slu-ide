package dev.sluide.previews

import org.jsoup.Jsoup
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebPagesTest {
    private val rocket = "🚀"
    private val rocketRef = "&#x1f680;"

    private fun String.hasSurrogate() = any { it.isSurrogate() }

    @Test
    fun `a page without surrogates is returned as the same instance`() {
        val html = "<!DOCTYPE html><html><body><p title=\"é\">✅ ★ é</p></body></html>"
        assertSame(html, escapeSupplementary(html))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "<p>x🚀y</p>",
            "<p>🚀😀</p>",
            "<p>✅ ★ 🚀 é</p>",
            "<p title=\"a🚀b\" data-source='🚀'>t</p>",
        ],
    )
    fun `astral characters in text and attributes become references that parse back`(html: String) {
        val out = escapeSupplementary(html)
        assertFalse(out.hasSurrogate(), out)
        assertEquals(Jsoup.parse(html).body().html(), Jsoup.parse(out).body().html())
        assertEquals(Jsoup.parse(html).body().text(), Jsoup.parse(out).body().text())
    }

    @Test
    fun `script content gets the pair as unicode escapes`() {
        val out = escapeSupplementary("<script>window.x = '$rocket'</script>")
        assertFalse(out.hasSurrogate(), out)
        assertContains(out, "window.x = '\\uD83D\\uDE80'")
    }

    @Test
    fun `an astral character at the very end of a script is escaped`() {
        val out = escapeSupplementary("<script>var s = `$rocket`; //$rocket</script>")
        assertFalse(out.hasSurrogate(), out)
        assertTrue(out.contains("//\\uD83D\\uDE80</script>"), out)
    }

    @Test
    fun `style content gets a CSS escape ended by a space`() {
        val out = escapeSupplementary("<style>#a::before { content: \"$rocket\" }</style>")
        assertFalse(out.hasSurrogate(), out)
        assertContains(out, "content: \"\\1F680 \"")
    }

    @Test
    fun `doctype, html attributes and comments are kept`() {
        val out =
            escapeSupplementary("<!DOCTYPE html><html lang=\"de\"><!-- note --><body><p>$rocket</p></body></html>")
        assertContains(out, "<!doctype html>", ignoreCase = true)
        assertContains(out, "<html lang=\"de\">")
        assertContains(out, "<!-- note -->")
    }

    @Test
    fun `an xmp element keeps the raw character, a known limit`() {
        // Found with jsoup: raw-text elements are written unescaped, so the emoji stays broken there but is not
        // turned into visible reference text.
        assertContains(escapeSupplementary("<xmp>$rocket</xmp>"), "<xmp>$rocket</xmp>")
    }

    @Test
    fun `the page is written without pretty printing`() {
        assertEquals(
            "<html><head></head><body><p>x&#x1f680;y</p></body></html>",
            escapeSupplementary("<p>x$rocket" + "y</p>"),
        )
        assertEquals(
            "<html><head></head><body><div> <span>a</span> <span>b$rocketRef</span>\n</div>\n<p>c</p></body></html>",
            escapeSupplementary("<div> <span>a</span> <span>b$rocket</span>\n</div>\n<p>c</p>"),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["pre", "listing"])
    fun `a newline after the start tag of a pre-like element survives`(tag: String) {
        // jsoup's parser drops the first newline after these tags and its writer does not add it back; WebKit drops
        // one again, so the writer's output needs the newline the source had.
        val out = escapeSupplementary("<$tag>\n\nfirst</$tag>$rocket")
        assertContains(out, "<$tag>\n\nfirst</$tag>")
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["<pre>first\nsecond</pre>", "<pre></pre>", "<pre><b>x</b>\n</pre>", "<pre>\n<b>x</b></pre>"],
    )
    fun `a pre without a leading newline in its text gets none`(pre: String) {
        val expected = pre.replace("\n<b>", "<b>")
        assertContains(escapeSupplementary(pre + rocket), expected)
    }
}
