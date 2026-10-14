package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals

class JsxHighlightingTest {
    private val jsx =
        Highlighted(
            """
            |import { Item } from "./item";
            |
            |export function App({ items, n }) {
            |  const big = n < limit && n > 0; // <b>not a tag</b>
            |  return (
            |    <>
            |      <ul className="list" onClick={() => select(n)}>
            |        {items.map((i) => <Item.Row key={i.id} label='x' disabled />)}
            |      </ul>
            |      <Footer
            |        year={2024}
            |      />
            |    </>
            |  );
            |}
            |
            """.trimMargin(),
            RSyntaxHighlighter.javaScriptReact,
        )

    @Test
    fun tags() {
        assertEquals(setOf(TokenStyle.TAG), jsx.stylesOf("<ul"))
        assertEquals(setOf(TokenStyle.TAG), jsx.stylesOf("</ul>"))
        assertEquals(setOf(TokenStyle.TAG), jsx.stylesOf("<Item.Row"))
        assertEquals(setOf(TokenStyle.TAG), jsx.stylesOf("<Footer"))
        assertEquals(setOf(TokenStyle.TAG), jsx.stylesOf("<>"))
        assertEquals(setOf(TokenStyle.TAG), jsx.stylesOf("</>"))
        // The `>` closing an opening tag, and `/>` of self-closing tags on the same or a later line.
        assertEquals(setOf(TokenStyle.TAG), jsx.stylesOf(">\n        {items"))
        assertEquals(setOf(TokenStyle.TAG), jsx.stylesOf("/>"))
        assertEquals(setOf(TokenStyle.TAG), jsx.stylesOf("/>", 1))
    }

    @Test
    fun attributes() {
        for (attribute in listOf("className", "onClick", "key", "label")) {
            assertEquals(setOf(TokenStyle.ATTRIBUTE), jsx.stylesOf(attribute), attribute)
        }
        assertEquals(setOf(TokenStyle.ATTRIBUTE), jsx.stylesOf("year"))
        assertEquals(setOf(TokenStyle.STRING), jsx.stylesOf("\"list\""))
        assertEquals(setOf(TokenStyle.NUMBER), jsx.stylesOf("2024"))
    }

    @Test
    fun `code stays code`() {
        // An arrow's `>` inside a tag is no tag end, a comparison is no tag, comments stay comments.
        assertEquals(setOf(TokenStyle.OPERATOR), jsx.stylesOf("=>"))
        assertEquals(setOf(TokenStyle.OPERATOR), jsx.stylesOf("< limit"))
        assertEquals(setOf(TokenStyle.OPERATOR), jsx.stylesOf(" > "))
        assertEquals(setOf(TokenStyle.COMMENT), jsx.stylesOf("// <b>not a tag</b>"))
        assertEquals(setOf(TokenStyle.KEYWORD), jsx.stylesOf("const"))
        assertEquals(emptySet(), jsx.stylesOf("big"))
    }

    @Test
    fun `spans are well-formed`() = jsx.assertWellFormed()
}
