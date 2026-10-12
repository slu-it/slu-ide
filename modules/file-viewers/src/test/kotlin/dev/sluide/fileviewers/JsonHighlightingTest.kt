package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonHighlightingTest {
    private val json =
        Highlighted(
            """
            |{
            |  // build settings
            |  "name": "sluide",
            |  "size": -1.5e3,
            |  "count": 42,
            |  "enabled": true,
            |  "parent": null,
            |  "tags": ["a", "b"]
            |}
            |
            """.trimMargin(),
            RSyntaxHighlighter.json,
        )

    @Test
    fun `keys are properties, values keep their kind`() {
        for (key in listOf("\"name\"", "\"size\"", "\"enabled\"", "\"tags\"")) {
            assertEquals(setOf(TokenStyle.PROPERTY), json.stylesOf(key), key)
        }
        assertEquals(setOf(TokenStyle.STRING), json.stylesOf("\"sluide\""))
        assertEquals(setOf(TokenStyle.STRING), json.stylesOf("\"a\""))
        assertEquals(setOf(TokenStyle.NUMBER), json.stylesOf("-1.5e3"))
        assertEquals(setOf(TokenStyle.NUMBER), json.stylesOf("42"))
        assertEquals(setOf(TokenStyle.KEYWORD), json.stylesOf("true"))
        assertEquals(setOf(TokenStyle.KEYWORD), json.stylesOf("null"))
    }

    @Test
    fun `line comments`() {
        assertEquals(setOf(TokenStyle.COMMENT), json.stylesOf("// build settings"))
    }

    @Test
    fun `spans are well-formed`() = json.assertWellFormed()
}
