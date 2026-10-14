package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where [FencedCodeHighlighter] finds code blocks. The only known language, `kt`, styles its whole block as `type`;
 * Markdown outside blocks is styled as `comment`.
 */
class FencedCodeHighlighterTest {
    private val wholeText = SyntaxHighlighter { listOf(HighlightSpan(0, it.length, TokenStyle.TYPE)) }

    private val highlighter =
        FencedCodeHighlighter(
            SyntaxHighlighter {
                if (it.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        HighlightSpan(0, it.length, TokenStyle.COMMENT),
                    )
                }
            },
        ) { if (it == "kt") wholeText else null }

    private fun styled(
        text: String,
        style: TokenStyle,
    ) = highlighter.highlight(text).filter { it.style == style }.map { text.substring(it.start, it.end) }

    private fun code(text: String) = styled(text, TokenStyle.TYPE)

    @Test
    fun `only the content of a fence with a known language is highlighted`() {
        assertEquals(listOf("val a = 1\nval b = 2\n"), code("intro\n```kt\nval a = 1\nval b = 2\n```\noutro\n"))
        assertEquals(listOf("a\n"), code("~~~ kt   extra words\na\n~~~\n"))
        assertEquals(listOf("a\n"), code("```KT\na\n```\n"))
    }

    @Test
    fun `fences without or with an unknown language are strings`() {
        assertEquals(listOf("```", "a\n", "```"), styled("```\na\n```\n", TokenStyle.STRING))
        assertEquals(listOf("```python", "a\n", "```"), styled("```python\na\n```\n", TokenStyle.STRING))
    }

    @Test
    fun `fence lines are strings, the text around them Markdown`() {
        val text = "intro\n```kt\na\n```\noutro"
        assertEquals(listOf("```kt", "```"), styled(text, TokenStyle.STRING))
        assertEquals(listOf("intro\n", "outro"), styled(text, TokenStyle.COMMENT))
    }

    @Test
    fun `a fence ends at a line of at least as many of the same characters`() {
        assertEquals(listOf("```\n~~~~\na\n"), code("````kt\n```\n~~~~\na\n`````  \nafter\n"))
    }

    @Test
    fun `an unclosed fence runs to the end of the text`() {
        assertEquals(listOf("a\nb"), code("```kt\na\nb"))
        assertEquals(emptyList(), code("```kt\n"))
    }

    @Test
    fun `indentation and line endings`() {
        assertEquals(listOf("a\r\n"), code("```kt\r\na\r\n```\r\n"))
        assertEquals(listOf("   a\n"), code("   ```kt\n   a\n   ```\n"))
        assertEquals(emptyList(), code("    ```kt\n    a\n    ```\n"))
        assertEquals(listOf("    ```kt\n    a\n    ```\n"), styled("    ```kt\n    a\n    ```\n", TokenStyle.COMMENT))
    }

    @Test
    fun `a backtick fence's info string has no backticks`() {
        assertEquals(emptyList(), code("```kt`\na\n```\n"))
    }

    @Test
    fun `several blocks keep their offsets`() {
        val text = "```kt\na\n```\ntext\n```kt\nbb\n```\n"
        assertEquals(
            listOf(6 until 8, 23 until 26),
            highlighter.highlight(text).filter { it.style == TokenStyle.TYPE }.map { it.start until it.end },
        )
    }
}
