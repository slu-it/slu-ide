package dev.sluide.coloring.detect

import dev.sluide.coloring.detect.SourceKind.COMPONENT
import dev.sluide.coloring.detect.SourceKind.MARKUP
import dev.sluide.coloring.detect.SourceKind.SCRIPT
import dev.sluide.coloring.detect.SourceKind.STYLE
import dev.sluide.coloring.detect.SourceKind.XML
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ColorFinderTest {
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("cases")
    fun `finds the colors a context allows and nothing else`(
        kind: SourceKind,
        line: String,
        expected: List<String>,
    ) {
        assertEquals(expected, ColorFinder.find(line, kind).map { it.text })
    }

    @Test
    fun `a found color has its inclusive column range and parsed value`() {
        val found = ColorFinder.find("  color: #ff0000;", STYLE).single()
        assertEquals(9..15, found.range)
        assertEquals("#ff0000", found.text)
        assertEquals(1.0, found.color.toSrgb().c0, 1e-9)
    }

    @ParameterizedTest
    @CsvSource("7,#fff", "9,#fff", "10,#fff", "11,#fff", "6,", "12,", "0,")
    fun `at finds the color under or just before the caret`(
        column: Int,
        expected: String?,
    ) {
        assertEquals(expected, ColorFinder.at("color: #fff; x", STYLE, column)?.text)
    }

    @Test
    fun `at prefers the color containing the column over one ending right before it`() {
        assertEquals("#fff", ColorFinder.at("color: rgb(1 2 3)#fff", STYLE, 17)?.text)
        assertEquals("rgb(1 2 3)", ColorFinder.at("color: rgb(1 2 3)#fff", STYLE, 16)?.text)
        assertNull(ColorFinder.at("color: 1px", STYLE, 8))
    }

    @Test
    fun `a line over the maximum length is not searched`() {
        val line = "color: #fff;"
        assertEquals(1, ColorFinder.find(line.padEnd(ColorFinder.MAX_LINE_LENGTH), STYLE).size)
        assertEquals(emptyList(), ColorFinder.find(line.padEnd(ColorFinder.MAX_LINE_LENGTH + 1), STYLE))
    }

    @Test
    fun `the number of results is capped`() {
        val found = ColorFinder.find("color: " + "#fff ".repeat(100), STYLE)
        assertEquals(ColorFinder.MAX_RESULTS, found.size)
    }

    @ParameterizedTest
    @CsvSource(
        "a.css,STYLE",
        "a.scss,STYLE",
        "a.sass,STYLE",
        "a.less,STYLE",
        "a.CSS,STYLE",
        "app.min.css,STYLE",
        "a.html,MARKUP",
        "a.htm,MARKUP",
        "a.xhtml,MARKUP",
        "a.svg,MARKUP",
        "a.xml,XML",
        "a.js,SCRIPT",
        "a.mjs,SCRIPT",
        "a.cjs,SCRIPT",
        "a.ts,SCRIPT",
        "a.mts,SCRIPT",
        "a.cts,SCRIPT",
        "a.jsx,SCRIPT",
        "a.TSX,SCRIPT",
        "a.vue,COMPONENT",
        "a.svelte,COMPONENT",
        "a.kt,",
        "a.md,",
        "Makefile,",
        "css,",
        "a.,",
    )
    fun `the kind follows the extension`(
        fileName: String,
        expected: SourceKind?,
    ) {
        assertEquals(expected, SourceKind.of(fileName))
    }

    companion object {
        @JvmStatic
        fun cases(): Stream<Arguments> =
            Stream.of(
                // style
                row(STYLE, "  color: #fff;", "#fff"),
                row(STYLE, "background: rgb(0 0 0 / .5);", "rgb(0 0 0 / .5)"),
                row(STYLE, "border: 1px solid red;", "red"),
                row(STYLE, "color: oklch(70% 0.1 200)", "oklch(70% 0.1 200)"),
                row(STYLE, "--brand: #3b82f6;", "#3b82f6"),
                row(STYLE, "a { color: blue }", "blue"),
                row(STYLE, "color: #FFF; background: Red;", "#FFF", "Red"),
                row(STYLE, "#fed {"),
                row(STYLE, "#abc, #def {"),
                row(STYLE, "div#bad > p {"),
                row(STYLE, "#abc .x"),
                row(STYLE, "color: var(--x)"),
                row(STYLE, "color: rgba(\$c, .5)"),
                row(STYLE, "background: url(#fff)"),
                row(STYLE, "background: var(red)"),
                row(STYLE, ".red { }"),
                row(STYLE, "@include red-theme;"),
                row(STYLE, "color: transparent"),
                row(STYLE, "color: #12345;"),
                row(STYLE, "a:hover {"),
                row(STYLE, "color: #fff; // fixes #123", "#fff"),
                row(STYLE, "// color: red"),
                row(STYLE, "background: url( //cdn/a.png) #fff", "#fff"),
                row(STYLE, "  color: #fff;// fixes #123", "#fff"),
                row(STYLE, "a { color: #fff }// #123", "#fff"),
                row(STYLE, "    0 0 0 1px #000,", "#000"),
                row(STYLE, "    #fff 0%,", "#fff"),
                row(STYLE, "    #abc,"),
                row(STYLE, "    inset 0 0 red;"),
                // known limitations: comments, selectors without { on the line, pseudo-class selectors, quotes
                row(STYLE, "/* fixes #123 */", "#123"),
                row(STYLE, "#abc", "#abc"),
                row(STYLE, "li:hover #add {", "#add"),
                row(STYLE, "content: \"red\"", "red"),
                // markup
                row(MARKUP, "<rect fill=\"red\" stroke=\"#00f\"/>", "red", "#00f"),
                row(MARKUP, "<div style=\"color: hsl(0 100% 50%)\">", "hsl(0 100% 50%)"),
                row(MARKUP, "<div style=\"color: red\">", "red"),
                row(MARKUP, "<div class=\"red\">"),
                row(MARKUP, "<div title=\"red\">"),
                row(MARKUP, "&#123;"),
                row(MARKUP, "<p>The red fox</p>"),
                row(MARKUP, "<p>#fff</p>"),
                row(MARKUP, "<a href=\"#top\">"),
                // known limitation: any quoted value takes hex
                row(MARKUP, "<a href=\"#fed\">", "#fed"),
                row(MARKUP, "<rect fill=\"url(#abc)\"/>"),
                row(MARKUP, "<a href=\"https://x.y\">#123</a>"),
                row(MARKUP, "<svg xmlns:xlink=\"http://x\"> <text>#123</text>"),
                row(MARKUP, "<!-- TODO: fixes #123 -->"),
                row(MARKUP, "<input name=\"red\">"),
                // known limitations: apostrophes open a string, no names in <style> blocks
                row(MARKUP, "<p>don't #fff</p>", "#fff"),
                row(MARKUP, "<li>Fixed: #123</li>", "#123"),
                row(MARKUP, "  color: red;"),
                // xml: Android reads #AARRGGBB, so 4 and 8 digits are not CSS
                row(XML, "android:background=\"#80000000\""),
                row(XML, "android:color=\"#ff0000\"", "#ff0000"),
                row(XML, "<item name=\"colorPrimary\">#6200EE</item>", "#6200EE"),
                row(XML, "<color name=\"x\">#80ff0000</color>"),
                row(XML, "<color name=\"x\">a #f00 b</color>"),
                row(XML, "android:color=\"#f00f\""),
                // script
                row(SCRIPT, "const c = '#fff'", "#fff"),
                row(SCRIPT, "const c = `rgb(1 2 3)`", "rgb(1 2 3)"),
                row(SCRIPT, "\"red\"", "red"),
                row(SCRIPT, "x = \"it\\\"s #fff\"", "#fff"),
                row(SCRIPT, "this.#add()"),
                row(SCRIPT, "// fixes #123"),
                row(SCRIPT, "x = 1 // 'red'"),
                row(SCRIPT, "const red = 1"),
                row(SCRIPT, "\"the red fox\""),
                row(SCRIPT, "x = #abc"),
                row(SCRIPT, "rgb(1, 2, 3)"),
                row(SCRIPT, "/* fixes #123 */ x = '#fff'"),
                row(SCRIPT, " * Don't touch #123 here"),
                row(SCRIPT, "<div className=\"red\">"),
                row(SCRIPT, "<input name=\"red\" />"),
                row(SCRIPT, "<div className={'red'} fill={'blue'} />", "blue"),
                row(SCRIPT, "<rect fill=\"red\" />", "red"),
                row(SCRIPT, "const c = 'éred'"),
                row(SCRIPT, "fetch('http://x/#fff')", "#fff"),
                // known limitation: an apostrophe in JSX text opens a string
                row(SCRIPT, "<p>Don't see #123</p>", "#123"),
                // components
                row(COMPONENT, "<rect fill=\"blue\" />", "blue"),
                row(COMPONENT, "  color: #fff;", "#fff"),
                row(COMPONENT, "  border: 1px solid red;", "red"),
                row(COMPONENT, "const c = 'red'", "red"),
                row(COMPONENT, "const c = '#abc'", "#abc"),
                row(COMPONENT, "<p>The red fox</p>"),
                row(COMPONENT, "#fed {"),
                row(COMPONENT, "// fixes #123"),
                row(COMPONENT, "  background: url(//cdn/a.png) #fff;", "#fff"),
                row(COMPONENT, "<div :style=\"{ color: 'red' }\">", "red"),
                row(COMPONENT, "<li>Fixed: #123</li>", "#123"),
                row(COMPONENT, " * Note: see #123"),
                row(COMPONENT, "<p>Fixed #123 today</p>"),
                row(COMPONENT, "x = #abc"),
                row(COMPONENT, "<div class=\"red\">"),
                row(COMPONENT, "<div style=\"color: red\">", "red"),
            )

        private fun row(
            kind: SourceKind,
            line: String,
            vararg expected: String,
        ): Arguments = Arguments.of(kind, line, expected.toList())
    }
}
