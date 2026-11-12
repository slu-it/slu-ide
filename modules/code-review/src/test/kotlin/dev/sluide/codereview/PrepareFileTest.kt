package dev.sluide.codereview

import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.HighlightSpan
import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TokenStyle
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PrepareFileTest {
    private val file = ReviewFile(ReviewChange.MODIFIED, "src/App.kt")

    private fun text(prepared: PreparedFile) = prepared.body as PreparedBody.Text

    @ParameterizedTest
    @ValueSource(strings = ["a\nb\nc", "a\nb\nc\n", "a\r\nb\r\nc\r\n", "a\r\nb\nc"])
    fun `lines are split at line breaks, a final one starting no line`(content: String) {
        assertEquals(listOf("a", "b", "c"), textLines(content))
    }

    @Test
    fun `an empty text has no lines and a lone line break one empty line`() {
        assertEquals(emptyList(), textLines(""))
        assertEquals(listOf(""), textLines("\n"))
        assertEquals(listOf("", ""), textLines("\n\n"))
    }

    @Test
    fun `a token across lines is cut per line, relative to each line`() {
        val content = "x /* a\nb\r\nc */ y\n"
        val comment = HighlightSpan(2, content.indexOf("*/") + 2, TokenStyle.COMMENT)
        val name = HighlightSpan(content.indexOf("y"), content.indexOf("y") + 1, TokenStyle.KEYWORD)
        val highlighter = SyntaxHighlighter { listOf(HighlightSpan(0, 1, TokenStyle.TYPE), comment, name) }

        val prepared = prepareFile(file, ReviewFileContent.Text(content, content, emptyList()), highlighter, false)

        assertEquals(
            listOf(
                listOf(HighlightSpan(0, 1, TokenStyle.TYPE), HighlightSpan(2, 6, TokenStyle.COMMENT)),
                listOf(HighlightSpan(0, 1, TokenStyle.COMMENT)),
                listOf(HighlightSpan(0, 4, TokenStyle.COMMENT), HighlightSpan(5, 6, TokenStyle.KEYWORD)),
            ),
            text(prepared).currentTokens,
        )
        assertEquals(text(prepared).currentTokens, text(prepared).originalTokens)
    }

    @Test
    fun `each text is highlighted once as a whole`() {
        val calls = AtomicInteger()
        val highlighter =
            SyntaxHighlighter { content ->
                calls.incrementAndGet()
                listOf(HighlightSpan(0, content.length, TokenStyle.STRING))
            }

        prepareFile(
            file,
            ReviewFileContent.Text("a\nb\nc\n", "a\nB\nc\n", listOf(LineChange(1..1, 1..1))),
            highlighter,
            false,
        )

        assertEquals(2, calls.get())
    }

    @Test
    fun `a text over the highlight limit has no tokens, the other side still has`() {
        val long = "x".repeat(CodeEditors.HIGHLIGHT_LIMIT) + "\ny\n"
        val highlighted = mutableListOf<Int>()
        val highlighter =
            SyntaxHighlighter { content ->
                highlighted += content.length
                listOf(HighlightSpan(0, 1, TokenStyle.KEYWORD))
            }

        val prepared =
            prepareFile(
                file,
                ReviewFileContent.Text("x\n", long, listOf(LineChange(1 until 1, 1..1))),
                highlighter,
                false,
            )

        assertEquals(listOf(2), highlighted)
        assertEquals(listOf(listOf(HighlightSpan(0, 1, TokenStyle.KEYWORD))), text(prepared).originalTokens)
        assertEquals(listOf(emptyList<HighlightSpan>(), emptyList()), text(prepared).currentTokens)
    }

    @Test
    fun `without a highlighter there are no tokens`() {
        val prepared =
            prepareFile(file, ReviewFileContent.Text("a\n", "b\n", listOf(LineChange(0..0, 0..0))), null, false)

        assertEquals(listOf(emptyList<HighlightSpan>()), text(prepared).currentTokens)
    }

    @Test
    fun `changed words are marked within modified lines, whitespace too unless it is ignored`() {
        val content = ReviewFileContent.Text("val x = 1\n", "val  x = 2\n", listOf(LineChange(0..0, 0..0)))

        val marked = text(prepareFile(file, content, null, ignoreWhitespace = false)).words
        val ignored = text(prepareFile(file, content, null, ignoreWhitespace = true)).words

        assertEquals(mapOf(0 to listOf(3..4, 9..9)), marked.current)
        assertEquals(mapOf(0 to listOf(9..9)), ignored.current)
        assertEquals(mapOf(0 to listOf(8..8)), ignored.original)
    }

    @Test
    fun `stats count the lines of both sides of each change, cut to the line counts`() {
        val original = (0 until 5).joinToString("") { "o$it\n" }
        val current = (0 until 6).joinToString("") { "c$it\n" }
        val changes =
            listOf(
                LineChange(1..1, 1 until 1), // removed: 1 line
                LineChange(3..9, 2..3), // modified, original side cut to 3..4: 2 and 2 lines
                LineChange(5 until 5, 4..9), // added, cut to 4..5: 2 lines
                LineChange(7..8, 8..9), // beyond both sides: dropped
            )

        val prepared = prepareFile(file, ReviewFileContent.Text(original, current, changes), null, false)

        assertEquals(4, prepared.added)
        assertEquals(3, prepared.removed)
        assertEquals(
            listOf(LineChange(1..1, 1 until 1), LineChange(3..4, 2..3), LineChange(5 until 5, 4..5)),
            text(prepared).changes,
        )
    }

    @Test
    fun `the content key follows the texts, not the changes or the whitespace mode`() {
        fun key(
            original: String,
            current: String,
            changes: List<LineChange> = listOf(LineChange(0..0, 0..0)),
            ignoreWhitespace: Boolean = false,
        ) = prepareFile(file, ReviewFileContent.Text(original, current, changes), null, ignoreWhitespace).contentKey

        assertEquals(key("a\n", "b\n"), key("a\n", "b\n", emptyList(), ignoreWhitespace = true))
        assertNotEquals(key("a\n", "b\n"), key("a\n", "c\n"))
        assertNotEquals(key("a\n", "b\n"), key("c\n", "b\n"))
        assertNotEquals(key("ab", ""), key("a", "b"))
    }

    @Test
    fun `a submodule names both commits by short hash, none for a missing side`() {
        val moved = prepareFile(file, ReviewFileContent.Submodule("abc1234567890", "def5678901234"), null, false)
        val added = prepareFile(file, ReviewFileContent.Submodule(null, "def5678901234"), null, false)

        assertEquals(PreparedBody.Submodule("abc1234 → def5678"), moved.body)
        assertEquals(PreparedBody.Submodule("none → def5678"), added.body)
    }
}
