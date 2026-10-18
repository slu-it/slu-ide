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
