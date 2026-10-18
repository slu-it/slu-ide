package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class JavaScriptHighlightingTest {
    private val js =
        Highlighted(
            """
            |// line comment
            |import { load } from './load.js';
            |
            |export default class Store extends Base {
            |  static size = 1_000n;
            |  async fetch(id) {
            |    const url = `items/${'$'}{id}?v=1`;
            |    const pattern = /ab+c/gi;
            |    return await load(url) ?? undefined;
            |  }
            |}
            |/* block */ console.log(typeof Store, null, true, 0xFF, 3.5, "double", 'single');
            |
            """.trimMargin(),
            RSyntaxHighlighter.javaScript,
        )

    @Test
    fun keywords() {
        for (keyword in listOf("import", "from", "export", "default", "class", "extends", "static", "async", "const")) {
            assertEquals(setOf(TokenStyle.KEYWORD), js.stylesOf(keyword), keyword)
        }
        for (keyword in listOf("return", "await", "undefined", "typeof", "null", "true")) {
            assertEquals(setOf(TokenStyle.KEYWORD), js.stylesOf(keyword), keyword)
        }
    }

    @Test
    fun `strings, template literals, regular expressions, comments and numbers`() {
        assertEquals(setOf(TokenStyle.STRING), js.stylesOf("'./load.js'"))
        assertEquals(setOf(TokenStyle.STRING), js.stylesOf("`items/"))
        assertEquals(emptySet(), js.stylesOf("{id}"), "interpolated code is not part of the string")
        assertEquals(setOf(TokenStyle.STRING), js.stylesOf("/ab+c/gi"))
        assertEquals(setOf(TokenStyle.STRING), js.stylesOf("\"double\""))
        assertEquals(setOf(TokenStyle.COMMENT), js.stylesOf("// line comment"))
        assertEquals(setOf(TokenStyle.COMMENT), js.stylesOf("/* block */"))
        for (number in listOf("1_000n", "0xFF", "3.5")) {
            assertEquals(setOf(TokenStyle.NUMBER), js.stylesOf(number), number)
        }
        assertEquals(emptySet(), js.stylesOf("Store"))
    }

    @Test
    fun `spans are well-formed`() = js.assertWellFormed()
}
