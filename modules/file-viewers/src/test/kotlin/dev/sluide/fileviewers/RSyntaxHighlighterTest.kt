package dev.sluide.fileviewers

import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RSyntaxHighlighterTest {
    private val python = PythonFileViewer.highlighter

    private val sample =
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
        """.trimMargin().replace("\n", "\r\n")

    private val spans = python.highlight(sample)

    /** Style of every span that overlaps [snippet] at its first occurrence. */
    private fun stylesOf(snippet: String): Set<TokenStyle> {
        val start = sample.indexOf(snippet).also { check(it >= 0) { "not in sample: $snippet" } }
        val end = start + snippet.length
        return spans.filter { it.start < end && it.end > start }.map { it.style }.toSet()
    }

    @Test
    fun `keywords and constants`() {
        for (keyword in listOf("import", "class", "def", "return", "True", "async", "await")) {
            assertEquals(setOf(TokenStyle.KEYWORD), stylesOf(keyword), keyword)
        }
    }

    @Test
    fun `strings, including a triple-quoted string over two lines`() {
        assertEquals(setOf(TokenStyle.STRING), stylesOf("'x'"))
        assertEquals(setOf(TokenStyle.STRING), stylesOf("\"\"\"Says hello."))
        assertEquals(setOf(TokenStyle.STRING), stylesOf("Over two lines.\"\"\""))
        assertEquals(setOf(TokenStyle.STRING), stylesOf("f'{name}!'"), "f-string prefix belongs to the string")
    }

    @Test
    fun `comments, numbers, builtins and decorators`() {
        assertEquals(setOf(TokenStyle.COMMENT), stylesOf("# note"))
        assertEquals(setOf(TokenStyle.NUMBER), stylesOf("42"))
        assertEquals(setOf(TokenStyle.NUMBER), stylesOf("3.14"))
        assertEquals(setOf(TokenStyle.NUMBER), stylesOf("1_000"), "underscores are valid, not an error")
        assertEquals(setOf(TokenStyle.BUILTIN), stylesOf("print"))
        assertEquals(setOf(TokenStyle.ANNOTATION), stylesOf("@decorator"))
    }

    @Test
    fun `plain identifiers are not styled`() {
        assertEquals(emptySet(), stylesOf("Greeter"))
        assertEquals(emptySet(), stylesOf("count"))
        assertEquals(emptySet(), stylesOf("other"))
    }

    @Test
    fun `spans are sorted, do not overlap and stay within the text`() {
        assertTrue(spans.isNotEmpty())
        assertTrue(spans.all { it.start in 0 until it.end && it.end <= sample.length })
        assertTrue(spans.zipWithNext().all { (a, b) -> a.end <= b.start })
        assertTrue(spans.none { sample.substring(it.start, it.end).contains('\r') }, "line breaks are not styled")
    }

    @Test
    fun `without corrections the plain lexer is used`() {
        val plain = RSyntaxHighlighter(SyntaxConstants.SYNTAX_STYLE_PYTHON)
        val text = "async x"

        assertEquals(emptyList(), plain.highlight(text))
    }

    @Test
    fun `empty text has no spans`() {
        assertEquals(emptyList(), python.highlight(""))
    }
}
