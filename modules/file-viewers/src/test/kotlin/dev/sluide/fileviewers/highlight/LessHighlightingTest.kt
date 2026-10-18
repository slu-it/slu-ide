package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class LessHighlightingTest {
    private val less =
        Highlighted(
            """
            |@import "base.less";
            |@accent: #c00; // brand
            |@supports (display: grid) { .grid { display: grid; } }
            |.card {
            |  color: @accent;
            |  .title { margin: 2px; }
            |}
            |
            """.trimMargin(),
            RSyntaxHighlighter.less,
        )

    @Test
    fun `variables and at-rules`() {
        assertEquals(setOf(TokenStyle.ANNOTATION), less.stylesOf("@accent"))
        assertEquals(setOf(TokenStyle.ANNOTATION), less.stylesOf("@accent", 1))
        assertEquals(setOf(TokenStyle.KEYWORD), less.stylesOf("@import"))
        assertEquals(setOf(TokenStyle.KEYWORD), less.stylesOf("@supports"))
    }

    @Test
    fun `selectors, values and comments`() {
        assertEquals(setOf(TokenStyle.TYPE), less.stylesOf(".card"))
        assertEquals(setOf(TokenStyle.KEYWORD), less.stylesOf("color"))
        assertEquals(setOf(TokenStyle.NUMBER), less.stylesOf("#c00"))
        assertEquals(setOf(TokenStyle.NUMBER), less.stylesOf("2px"))
        assertEquals(setOf(TokenStyle.COMMENT), less.stylesOf("// brand"))
    }

    @Test
    fun `spans are well-formed`() = less.assertWellFormed()
}
