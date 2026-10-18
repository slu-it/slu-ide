package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PythonHighlightingTest {
    private val python =
        Highlighted(
            """
            |import os
            |
            |@decorator
            |class Greeter:
            |    ${"\"\"\""}Says hello.
            |    Over two lines.${"\"\"\""}
            |
            |    def greet(self, name):  # note
            |        count = 42 + 3.14
            |        print('x', name)
            |        return True
            |
            |    async def later(self):
            |        await other(1_000, f'{name}!')
            |
            """.trimMargin().replace("\n", "\r\n"),
            RSyntaxHighlighter.python,
        )

    @Test
    fun `keywords and constants`() {
        for (keyword in listOf("import", "class", "def", "return", "True", "async", "await")) {
            assertEquals(setOf(TokenStyle.KEYWORD), python.stylesOf(keyword), keyword)
        }
    }

    @Test
    fun `strings, including a triple-quoted string over two lines`() {
        assertEquals(setOf(TokenStyle.STRING), python.stylesOf("'x'"))
        assertEquals(setOf(TokenStyle.STRING), python.stylesOf("\"\"\"Says hello."))
        assertEquals(setOf(TokenStyle.STRING), python.stylesOf("Over two lines.\"\"\""))
        assertEquals(setOf(TokenStyle.STRING), python.stylesOf("f'{name}!'"), "f-string prefix belongs to the string")
    }

    @Test
    fun `comments, numbers, builtins and decorators`() {
        assertEquals(setOf(TokenStyle.COMMENT), python.stylesOf("# note"))
        assertEquals(setOf(TokenStyle.NUMBER), python.stylesOf("42"))
        assertEquals(setOf(TokenStyle.NUMBER), python.stylesOf("3.14"))
        assertEquals(setOf(TokenStyle.NUMBER), python.stylesOf("1_000"), "underscores are valid, not an error")
        assertEquals(setOf(TokenStyle.BUILTIN), python.stylesOf("print"))
        assertEquals(setOf(TokenStyle.ANNOTATION), python.stylesOf("@decorator"))
    }

    @Test
    fun `plain identifiers are not styled`() {
        assertEquals(emptySet(), python.stylesOf("Greeter"))
        assertEquals(emptySet(), python.stylesOf("count"))
        assertEquals(emptySet(), python.stylesOf("other"))
    }

    @Test
    fun `spans are well-formed and do not include line breaks`() {
        python.assertWellFormed()
        assertTrue(python.spans.none { python.text.substring(it.start, it.end).contains('\r') })
    }

    @Test
    fun `without corrections the plain lexer is used`() {
        assertEquals(emptyList(), RSyntaxHighlighter("text/python").highlight("async x"))
    }

    @Test
    fun `empty text has no spans`() {
        assertEquals(emptyList(), RSyntaxHighlighter.python.highlight(""))
    }
}
