package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.Highlighted
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class HighlightersTest {
    @Test
    fun `known languages get the code views' highlighters`() {
        assertSame(RSyntaxHighlighter.json, Highlighters.forLanguage("json"))
        assertSame(RSyntaxHighlighter.xml, Highlighters.forLanguage("xml"))
        assertSame(RSyntaxHighlighter.html, Highlighters.forLanguage("html"))
        assertSame(RSyntaxHighlighter.javaScript, Highlighters.forLanguage("js"))
    }

    @Test
    fun `the named highlighters are those of their languages`() {
        val named =
            mapOf(
                "json" to Highlighters.json,
                "xml" to Highlighters.xml,
                "html" to Highlighters.html,
                "markdown" to Highlighters.markdown,
                "mermaid" to Highlighters.mermaid,
                "bicep" to Highlighters.bicep,
            )
        for ((language, highlighter) in named) assertSame(Highlighters.forLanguage(language), highlighter, language)
        assertSame(RSyntaxHighlighter.cookies, Highlighters.cookies)
        assertSame(RSyntaxHighlighter.pem, Highlighters.pem)
    }

    @Test
    fun `the language of a fence is its info string's first word, lower case`() {
        assertEquals("ts", Highlighters.fenceLanguage(" TS title=\"x\""))
        assertEquals("", Highlighters.fenceLanguage(""))
    }

    @Test
    fun `a known language highlights`() {
        val json = Highlighted("""{"a": 1}""", Highlighters.forLanguage("json") ?: error("no json"))

        assertEquals(setOf(TokenStyle.PROPERTY), json.stylesOf("\"a\""))
        assertEquals(setOf(TokenStyle.NUMBER), json.stylesOf("1"))
    }

    @Test
    fun `unknown languages have none`() {
        assertNull(Highlighters.forLanguage("cobol"))
        assertNull(Highlighters.forLanguage(""))
    }
}
