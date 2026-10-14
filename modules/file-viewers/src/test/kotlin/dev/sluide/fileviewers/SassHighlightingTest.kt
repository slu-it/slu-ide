package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals

class SassHighlightingTest {
    private val sass =
        Highlighted(
            """
            |@use "sass:math"
            |${'$'}primary: #333 // brand
            |=hover(${'$'}c)
            |  color: ${'$'}c
            |.btn, .link // buttons
            |  &:hover
            |    +hover(red)
            |    margin: 0 1rem
            |    font-family: serif
            |
            """.trimMargin(),
            RSyntaxHighlighter.sass,
        )

    @Test
    fun `selectors, properties and variables`() {
        assertEquals(setOf(TokenStyle.TYPE), sass.stylesOf(".btn, .link"))
        assertEquals(setOf(TokenStyle.TYPE), sass.stylesOf("&:hover"))
        for (property in listOf("color", "margin", "font-family")) {
            assertEquals(setOf(TokenStyle.KEYWORD), sass.stylesOf("$property:"), property)
        }
        assertEquals(setOf(TokenStyle.ANNOTATION), sass.stylesOf("\$primary"))
        assertEquals(setOf(TokenStyle.ANNOTATION), sass.stylesOf("\$c", 1))
    }

    @Test
    fun `at-rules and mixins`() {
        assertEquals(setOf(TokenStyle.KEYWORD), sass.stylesOf("@use"))
        assertEquals(setOf(TokenStyle.KEYWORD), sass.stylesOf("=hover"))
        assertEquals(setOf(TokenStyle.KEYWORD), sass.stylesOf("+hover"))
    }

    @Test
    fun `values are not styled as selectors`() {
        assertEquals(emptySet(), sass.stylesOf("red"))
        assertEquals(emptySet(), sass.stylesOf("serif"))
        assertEquals(setOf(TokenStyle.NUMBER), sass.stylesOf("#333"))
        assertEquals(setOf(TokenStyle.NUMBER), sass.stylesOf("1"))
        assertEquals(setOf(TokenStyle.COMMENT), sass.stylesOf("// brand"))
        assertEquals(setOf(TokenStyle.COMMENT), sass.stylesOf("// buttons"))
    }

    @Test
    fun `spans are well-formed`() = sass.assertWellFormed()
}
