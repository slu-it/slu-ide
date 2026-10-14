package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownHighlightingTest {
    private val document =
        """
        |# Getting started
        |
        |Some *emphasis*, **bold** and `inline code`, see [the docs](https://example.com/docs).
        |export the file as PDF.
        |
        |```ts
        |import x from "y"
        |handlers[name](event)
        |```
        |
        |<Chart data={points} />
        |
        """.trimMargin()

    private val md = Highlighted(document, RSyntaxHighlighter.markdown)

    private val mdx = Highlighted("import { Chart } from \"./chart\"\n\n$document", RSyntaxHighlighter.mdx)

    @Test
    fun `headings, emphasis and code`() {
        assertEquals(setOf(TokenStyle.KEYWORD), md.stylesOf("# Getting started"))
        assertEquals(setOf(TokenStyle.TYPE), md.stylesOf("*emphasis*"))
        assertEquals(setOf(TokenStyle.KEYWORD), md.stylesOf("**bold**"))
        assertEquals(setOf(TokenStyle.STRING), md.stylesOf("`inline code`"))
        assertEquals(emptySet(), md.stylesOf("Some"))
    }

    @Test
    fun `fenced code is a string, untouched by line rules`() {
        assertEquals(setOf(TokenStyle.STRING), md.stylesOf("import x from \"y\""))
        assertEquals(setOf(TokenStyle.STRING), md.stylesOf("handlers[name](event)"))
        assertEquals(setOf(TokenStyle.STRING), mdx.stylesOf("import x from \"y\""))
    }

    @Test
    fun `links and components`() {
        assertEquals(setOf(TokenStyle.ATTRIBUTE), md.stylesOf("[the docs](https://example.com/docs)"))
        assertEquals(setOf(TokenStyle.TAG), md.stylesOf("<Chart"))
        assertEquals(setOf(TokenStyle.TAG), md.stylesOf("/>"))
        assertEquals(setOf(TokenStyle.ATTRIBUTE), md.stylesOf("data"))
    }

    @Test
    fun `import and export lines are keywords in MDX only`() {
        assertEquals(setOf(TokenStyle.KEYWORD), mdx.stylesOf("import"))
        assertEquals(emptySet(), mdx.stylesOf("Chart"))
        assertEquals(setOf(TokenStyle.KEYWORD), mdx.stylesOf("export"))
        assertEquals(emptySet(), md.stylesOf("export the file"))
    }

    @Test
    fun `spans are well-formed`() {
        md.assertWellFormed()
        mdx.assertWellFormed()
    }
}
