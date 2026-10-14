package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownHighlightingTest {
    private val md =
        Highlighted(
            """
            |import { Chart } from "./chart"
            |
            |# Getting started
            |
            |Some *emphasis*, **bold** and `inline code`, see [the docs](https://example.com/docs).
            |
            |```ts
            |const answer = 42
            |```
            |
            |<Chart data={points} />
            |
            """.trimMargin(),
            RSyntaxHighlighter.markdown,
        )

    @Test
    fun `headings, emphasis and code`() {
        assertEquals(setOf(TokenStyle.KEYWORD), md.stylesOf("# Getting started"))
        assertEquals(setOf(TokenStyle.TYPE), md.stylesOf("*emphasis*"))
        assertEquals(setOf(TokenStyle.KEYWORD), md.stylesOf("**bold**"))
        assertEquals(setOf(TokenStyle.STRING), md.stylesOf("`inline code`"))
        assertEquals(setOf(TokenStyle.STRING), md.stylesOf("const answer = 42"))
    }

    @Test
    fun `links, MDX imports and inline JSX`() {
        assertEquals(setOf(TokenStyle.ATTRIBUTE), md.stylesOf("[the docs](https://example.com/docs)"))
        assertEquals(setOf(TokenStyle.KEYWORD), md.stylesOf("import"))
        assertEquals(emptySet(), md.stylesOf("Chart"))
        assertEquals(setOf(TokenStyle.TAG), md.stylesOf("<Chart").filterNot { it == TokenStyle.ATTRIBUTE }.toSet())
        assertEquals(emptySet(), md.stylesOf("Some"))
    }

    @Test
    fun `spans are well-formed`() = md.assertWellFormed()
}
