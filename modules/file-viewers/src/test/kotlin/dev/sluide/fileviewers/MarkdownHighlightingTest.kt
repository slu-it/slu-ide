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
        |```ts title="example.ts"
        |import x from "y"
        |handlers[name](event)
        |```
        |
        |```
        |const plain = 1
        |```
        |
        |~~~Python
        |def greet(): return 42
        |~~~
        |
        |```foo
        |const unknown = 2
        |```
        |
        |````md
        |# nested heading
        |```js
        |let nested = 1
        |```
        |````
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
    fun `fenced code with a language is highlighted as that language`() {
        assertEquals(setOf(TokenStyle.KEYWORD), md.stylesOf("import"))
        assertEquals(setOf(TokenStyle.KEYWORD), md.stylesOf("from"))
        assertEquals(setOf(TokenStyle.STRING), md.stylesOf("\"y\""))
        assertEquals(emptySet(), md.stylesOf("handlers[name](event)"))
        assertEquals(setOf(TokenStyle.KEYWORD), md.stylesOf("def"))
        assertEquals(setOf(TokenStyle.NUMBER), md.stylesOf("42"))
        // A Markdown fence holds Markdown with its own fences; the ``` inside does not end the outer ```` fence.
        assertEquals(setOf(TokenStyle.KEYWORD), md.stylesOf("# nested heading"))
        assertEquals(setOf(TokenStyle.KEYWORD), md.stylesOf("let"))
        assertEquals(setOf(TokenStyle.TAG), md.stylesOf("<Chart"))
    }

    @Test
    fun `fenced code without a known language stays a string`() {
        assertEquals(setOf(TokenStyle.STRING), md.stylesOf("const plain = 1"))
        assertEquals(setOf(TokenStyle.STRING), md.stylesOf("const unknown = 2"))
        // Fence lines keep their Markdown style.
        assertEquals(setOf(TokenStyle.STRING), md.stylesOf("```ts"))
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
        assertEquals(setOf(TokenStyle.KEYWORD), mdx.stylesOf("import", 1))
        assertEquals(setOf(TokenStyle.KEYWORD), mdx.stylesOf("export"))
        assertEquals(emptySet(), md.stylesOf("export the file"))
    }

    @Test
    fun `MDX fences are highlighted too`() {
        val jsx =
            Highlighted("export const a = 1\n\n```jsx\nconst el = <Card title=\"x\" />\n```\n", RSyntaxHighlighter.mdx)
        assertEquals(setOf(TokenStyle.TAG), jsx.stylesOf("<Card"))
        assertEquals(setOf(TokenStyle.ATTRIBUTE), jsx.stylesOf("title"))
        assertEquals(setOf(TokenStyle.KEYWORD), jsx.stylesOf("const", 1))
        jsx.assertWellFormed()
    }

    @Test
    fun `spans are well-formed`() {
        md.assertWellFormed()
        mdx.assertWellFormed()
    }
}
