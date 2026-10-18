package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlHighlightingTest {
    private val html =
        Highlighted(
            """
            |<!DOCTYPE html>
            |<html lang="en">
            |<head>
            |  <style>body { margin: 0; }</style>
            |  <script>
            |    const greeting = "hi"; // inline
            |  </script>
            |</head>
            |<!-- page content -->
            |<body class='main'>Hello &nbsp; <b>world</b></body>
            |</html>
            |
            """.trimMargin(),
            RSyntaxHighlighter.html,
        )

    @Test
    fun `markup`() {
        assertEquals(setOf(TokenStyle.ANNOTATION), html.stylesOf("<!DOCTYPE html>"))
        assertEquals(setOf(TokenStyle.TAG), html.stylesOf("<html"))
        assertEquals(setOf(TokenStyle.TAG), html.stylesOf("</body>"))
        assertEquals(setOf(TokenStyle.ATTRIBUTE), html.stylesOf("lang"))
        assertEquals(setOf(TokenStyle.STRING), html.stylesOf("\"en\""))
        assertEquals(setOf(TokenStyle.STRING), html.stylesOf("'main'"))
        assertEquals(setOf(TokenStyle.COMMENT), html.stylesOf("<!-- page content -->"))
        assertEquals(setOf(TokenStyle.NUMBER), html.stylesOf("&nbsp;"))
        assertEquals(emptySet(), html.stylesOf("Hello"))
    }

    @Test
    fun `embedded script and style`() {
        assertEquals(setOf(TokenStyle.KEYWORD), html.stylesOf("const"))
        assertEquals(setOf(TokenStyle.STRING), html.stylesOf("\"hi\""))
        assertEquals(setOf(TokenStyle.COMMENT), html.stylesOf("// inline"))
        assertEquals(setOf(TokenStyle.KEYWORD), html.stylesOf("margin"), "CSS property")
        assertEquals(emptySet(), html.stylesOf("greeting"))
    }

    @Test
    fun `spans are well-formed`() = html.assertWellFormed()
}
