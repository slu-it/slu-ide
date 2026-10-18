package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class XmlHighlightingTest {
    private val xml =
        Highlighted(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE project>
            |<!-- a comment
            |     over two lines -->
            |<project xmlns:m="urn:maven" id='p1'>
            |  <m:name>Sample &amp; more</m:name>
            |  <![CDATA[ raw <text> ]]>
            |</project>
            |
            """.trimMargin(),
            RSyntaxHighlighter.xml,
        )

    @Test
    fun `tags, attributes and values`() {
        assertEquals(setOf(TokenStyle.TAG), xml.stylesOf("<project"))
        assertEquals(setOf(TokenStyle.TAG), xml.stylesOf("<m:name>"))
        assertEquals(setOf(TokenStyle.TAG), xml.stylesOf("</project>"))
        assertEquals(setOf(TokenStyle.ATTRIBUTE), xml.stylesOf("xmlns:m"))
        assertEquals(setOf(TokenStyle.STRING), xml.stylesOf("\"urn:maven\""))
        assertEquals(setOf(TokenStyle.STRING), xml.stylesOf("'p1'"))
        assertEquals(emptySet(), xml.stylesOf("Sample"))
    }

    @Test
    fun `comments over several lines, declarations, entities and CDATA`() {
        assertEquals(setOf(TokenStyle.COMMENT), xml.stylesOf("<!-- a comment"))
        assertEquals(setOf(TokenStyle.COMMENT), xml.stylesOf("over two lines -->"))
        assertEquals(setOf(TokenStyle.ANNOTATION), xml.stylesOf("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertEquals(setOf(TokenStyle.ANNOTATION), xml.stylesOf("<!DOCTYPE project>"))
        assertEquals(setOf(TokenStyle.NUMBER), xml.stylesOf("&amp;"))
        assertEquals(setOf(TokenStyle.TAG), xml.stylesOf("<![CDATA["))
        assertEquals(setOf(TokenStyle.STRING), xml.stylesOf(" raw <text> "))
    }

    @Test
    fun `spans are well-formed`() = xml.assertWellFormed()
}
