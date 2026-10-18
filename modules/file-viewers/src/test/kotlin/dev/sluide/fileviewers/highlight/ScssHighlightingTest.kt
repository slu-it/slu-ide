package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class ScssHighlightingTest {
    private val scss =
        Highlighted(
            """
            |@use "sass:math";
            |${'$'}primary: #333; // brand
            |@mixin hover(${'$'}c) { &:hover { color: darken(${'$'}c, 10%); } }
            |.card {
            |  .title { width: #{${'$'}w}px; }
            |  @include hover(${'$'}primary);
            |}
            |
            """.trimMargin(),
            RSyntaxHighlighter.scss,
        )

    @Test
    fun `variables, at-rules and mixins`() {
        assertEquals(setOf(TokenStyle.ANNOTATION), scss.stylesOf("\$primary"))
        assertEquals(setOf(TokenStyle.ANNOTATION), scss.stylesOf("\$c"))
        assertEquals(setOf(TokenStyle.ANNOTATION), scss.stylesOf("\$w"))
        for (rule in listOf("@use", "@mixin", "@include")) {
            assertEquals(setOf(TokenStyle.KEYWORD), scss.stylesOf(rule), rule)
        }
    }

    @Test
    fun `values and comments`() {
        assertEquals(setOf(TokenStyle.STRING), scss.stylesOf("\"sass:math\""))
        assertEquals(setOf(TokenStyle.NUMBER), scss.stylesOf("#333"))
        assertEquals(setOf(TokenStyle.NUMBER), scss.stylesOf("10%"))
        assertEquals(setOf(TokenStyle.BUILTIN), scss.stylesOf("darken"))
        assertEquals(setOf(TokenStyle.COMMENT), scss.stylesOf("// brand"))
        assertEquals(setOf(TokenStyle.TYPE), scss.stylesOf(".card"))
    }

    @Test
    fun `spans are well-formed`() = scss.assertWellFormed()
}
