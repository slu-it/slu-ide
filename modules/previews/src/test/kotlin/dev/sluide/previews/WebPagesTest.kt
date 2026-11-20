package dev.sluide.previews

import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val ROCKET = "\uD83D\uDE80"
private const val ROCKET_REF = "&#x1f680;"

class WebPagesTest {
    private fun String.hasSurrogate() = any { it.isSurrogate() }

    @Test
    fun `a page without surrogates is returned as the same instance`() {
        val html = "<!DOCTYPE html><html><body><p title=\"é\">é</p></body></html>"
        assertSame(html, preparePage(html))
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
        val out = preparePage(html)
        assertFalse(out.hasSurrogate(), out)
        val parsed = Jsoup.parse(out)
        parsed.select("span[data-emoji]").forEach { it.unwrap() }
        assertEquals(Jsoup.parse(html).body().html(), parsed.body().html())
        assertEquals(Jsoup.parse(html).body().text(), parsed.body().text())
    }

    @Test
    fun `script content gets the pair as unicode escapes`() {
        val out = preparePage("<script>window.x = '$ROCKET'</script>")
        assertFalse(out.hasSurrogate(), out)
        assertContains(out, "window.x = '\\uD83D\\uDE80'")
    }

    @Test
    fun `an astral character at the very end of a script is escaped`() {
        val out = preparePage("<script>var s = `$ROCKET`; //$ROCKET</script>")
        assertFalse(out.hasSurrogate(), out)
        assertTrue(out.contains("//\\uD83D\\uDE80</script>"), out)
    }

    @Test
    fun `style content gets a CSS escape ended by a space`() {
        val out = preparePage("<style>#a::before { content: \"$ROCKET\" }</style>")
        assertFalse(out.hasSurrogate(), out)
        assertContains(out, "content: \"\\1F680 \"")
    }

    @Test
    fun `doctype, html attributes and comments are kept`() {
        val out =
            preparePage("<!DOCTYPE html><html lang=\"de\"><!-- note --><body><p>$ROCKET</p></body></html>")
        assertContains(out, "<!doctype html>", ignoreCase = true)
        assertContains(out, "<html lang=\"de\">")
        assertContains(out, "<!-- note -->")
    }

    @Test
    fun `an xmp element keeps the raw character, a known limit`() {
        // Found with jsoup: raw-text elements are written unescaped, so the emoji stays broken there but is not
        // turned into visible reference text.
        assertContains(preparePage("<xmp>$ROCKET</xmp>"), "<xmp>$ROCKET</xmp>")
    }

    @Test
    fun `the page is written without pretty printing`() {
        assertEquals(
            "<html><head></head><body><p>x<span data-emoji=\"\">&#x1f680;</span>y</p></body></html>",
            preparePage("<p>x$ROCKET" + "y</p>"),
        )
        assertEquals(
            "<html><head></head><body><div> <span>a</span> <span>b<span data-emoji=\"\">$ROCKET_REF</span></span>\n</div>\n<p>c</p></body></html>",
            preparePage("<div> <span>a</span> <span>b$ROCKET</span>\n</div>\n<p>c</p>"),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["pre", "listing"])
    fun `a newline after the start tag of a pre-like element survives`(tag: String) {
        // jsoup's parser drops the first newline after these tags and its writer does not add it back; WebKit drops
        // one again, so the writer's output needs the newline the source had.
        val out = preparePage("<$tag>\n\nfirst</$tag>$ROCKET")
        assertContains(out, "<$tag>\n\nfirst</$tag>")
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["<pre>first\nsecond</pre>", "<pre></pre>", "<pre><b>x</b>\n</pre>", "<pre>\n<b>x</b></pre>"],
    )
    fun `a pre without a leading newline in its text gets none`(pre: String) {
        val expected = pre.replace("\n<b>", "<b>")
        assertContains(preparePage(pre + ROCKET), expected)
    }

    private val flag = "\uD83C\uDDE9\uD83C\uDDEA"
    private val heart = "\u2764\uFE0F"

    private fun emojiSpans(html: String) = Jsoup.parse(preparePage(html)).select("span[data-emoji]").map { it.text() }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67",
            "\uD83C\uDDE9\uD83C\uDDEA",
            "\uD83D\uDC4D\uD83C\uDFFD",
            "1\uFE0F\u20E3",
            "\u2764\uFE0F",
            "\u2705",
            "\uD83D\uDE80",
            "\uD83C\uDDE6",
            "\uD83C\uDDFF",
            "a\uFE0F",
            "a\u20E3",
        ],
    )
    fun `an emoji cluster is wrapped whole in one span`(cluster: String) {
        assertEquals(listOf(cluster), emojiSpans("<p>a $cluster b</p>"))
        assertEquals("a $cluster b", Jsoup.parse(preparePage("<p>a $cluster b</p>")).body().text())
    }

    @Test
    fun `two adjacent emoji get a span each`() {
        assertEquals(listOf(ROCKET, "\u2705", flag), emojiSpans("<p>$ROCKET\u2705 $flag</p>"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "$ROCKET Headline|<span data-emoji=\"\">$ROCKET_REF</span> Headline",
            "Headline $ROCKET|Headline <span data-emoji=\"\">$ROCKET_REF</span>",
            "a $ROCKET b|a <span data-emoji=\"\">$ROCKET_REF</span> b",
        ],
    )
    fun `an emoji at the start, the end or the middle of a text is split out of it`(case: String) {
        val (text, expected) = case.split("|")
        assertContains(preparePage("<h2>$text</h2>"), "<h2>$expected</h2>")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "<textarea>$ROCKET</textarea>",
            "<select><option>$ROCKET</option></select>",
            "<svg><text>$ROCKET</text></svg>",
            "<math><mi>$ROCKET</mi></math>",
            "<xmp>$ROCKET</xmp>",
            "<noembed>$ROCKET</noembed>",
        ],
    )
    fun `an emoji is not wrapped where a span is not valid`(html: String) {
        assertFalse("data-emoji" in preparePage("<body>$html"), html)
    }

    @Test
    fun `an emoji in the title is not wrapped`() {
        assertFalse("data-emoji" in preparePage("<html><head><title>$ROCKET</title></head><body>x</body></html>"))
    }

    @Test
    fun `script and style content gets no span`() {
        assertEquals(
            emptyList(),
            emojiSpans("<script>var a = '$ROCKET'</script><style>a { content: '$ROCKET' }</style>"),
        )
    }

    @Test
    fun `a page that is prepared once is not changed by preparing it again`() {
        val once = preparePage("<h1>$ROCKET Head</h1><p>$flag$heart</p>")
        assertEquals(once, preparePage(once))
        assertEquals(listOf(ROCKET), emojiSpans("<p><span data-emoji=\"\">$ROCKET</span></p>"))
    }

    @Test
    fun `the character right after the regional indicators is not an emoji`() {
        // U+1F200 is no extended pictograph either
        assertEquals(emptyList(), emojiSpans("<p>a 🈀 b</p>"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["\uFE0F", "\u20E3", "\u2764", "\u00A9", "\u2705"])
    fun `a page whose only emoji char is one that can be part of a cluster takes the slow path`(char: String) {
        val html = "<p>x$char</p>"
        assertTrue(preparePage(html) != html, char)
    }

    @Test
    fun `a text node without an emoji char keeps its node`() {
        val document = Jsoup.parse("<p>plain \u00e9 text</p><p>$ROCKET</p>")
        val paragraph = document.select("p").first()
        val plain = paragraph?.childNode(0)
        assertTrue(plain is TextNode)
        wrapEmoji(document)
        assertSame(plain, paragraph?.childNode(0))
    }

    @Test
    fun `a long text with one emoji is prepared in linear time`() {
        // The text node is rebuilt from its clusters; joining them by repeated concatenation took seconds
        val html = "<pre>" + "abc def\n".repeat(125_000) + "$ROCKET</pre>"
        val out = assertTimeout<String>(Duration.ofMillis(1500)) { preparePage(html) }
        assertContains(out, "<span data-emoji=\"\">$ROCKET_REF</span>")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "<p>$ROCKET</p>|<p><span data-emoji=\"\">$ROCKET_REF</span></p>",
            "<span class=\"x\">$ROCKET</span>|<span class=\"x\"><span data-emoji=\"\">$ROCKET_REF</span></span>",
            "<b data-emoji=\"\">$ROCKET</b>|<b data-emoji=\"\"><span data-emoji=\"\">$ROCKET_REF</span></b>",
            "<span data-emoji=\"\">$ROCKET$ROCKET</span>|" +
                "<span data-emoji=\"\"><span data-emoji=\"\">$ROCKET_REF</span>" +
                "<span data-emoji=\"\">$ROCKET_REF</span></span>",
            "<span data-emoji=\"\">$ROCKET x</span>|<span data-emoji=\"\"><span data-emoji=\"\">$ROCKET_REF</span> x</span>",
            "<div data-emoji=\"user\">$ROCKET</div>|<div data-emoji=\"user\"><span data-emoji=\"\">$ROCKET_REF</span></div>",
        ],
    )
    fun `an emoji text is wrapped unless it is exactly the one emoji of a span of ours`(case: String) {
        val (html, expected) = case.split("|")
        assertContains(preparePage(html), expected)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "$ROCKET",
            "\u2705",
            "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67",
            "\uD83C\uDDE9\uD83C\uDDEA",
            "1\uFE0F\u20E3",
            "plain text",
            "\u00e9",
            "\uD835\uDC65",
            "\uD83C\uDE00",
            "1 2 3",
        ],
    )
    fun `containsEmoji agrees with the spans preparePage wraps`(text: String) {
        val wrapped = emojiSpans("<p>a $text b</p>").isNotEmpty()
        assertEquals(wrapped, containsEmoji("a $text b"), text)
    }

    @Test
    fun `containsEmoji is true for an emoji among thousands of plain chars and false for none`() {
        assertTrue(containsEmoji("abc ".repeat(10_000) + ROCKET))
        assertFalse(containsEmoji("abc \u00e9 ".repeat(10_000)))
    }
}
