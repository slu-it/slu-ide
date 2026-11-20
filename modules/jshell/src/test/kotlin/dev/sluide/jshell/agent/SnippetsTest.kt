package dev.sluide.jshell.agent

import jdk.jshell.JShell
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Splitting and selecting with the build JDK's real analysis; the expected offsets are those the recordings show. */
class SnippetsTest {
    companion object {
        // The local engine: the analysis needs no execution JVM.
        private val shell: JShell = JShell.builder().executionEngine("local").build()
        private val analysis = shell.sourceCodeAnalysis()

        @JvmStatic
        @AfterAll
        fun closeShell() = shell.close()

        // multi-snippet.jsonl, line 2
        private const val MULTI =
            "// leading comment\nint a = 1;\n\n/* block */ int b = a + 1\n\nvoid f() {\n  System.out.println(b);\n}\n" +
                "   \nf();   // trailing comment\nString s = \"x\"; s.length()\nint q = \"no\";\nclass C {}\n" +
                "import java.util.*;\nint broken = ("

        // caret.jsonl, line 2
        private const val CARET = "int a = 1;\n\nint b = a + 1;\n\n\nSystem.out.println(a + b);\n"
    }

    private fun spans(pieces: List<Piece>) = pieces.map { it.codeStart to it.end }

    @Test
    fun `a document splits like the jshell tool reads a script`() {
        // multi-snippet.jsonl, the snippetStarted events of lines 3 to 20
        assertEquals(
            listOf(
                (19 to 29),
                (43 to 56),
                (58 to 95),
                (100 to 126),
                (127 to 142),
                (143 to 153),
                (154 to 167),
                (168 to 178),
                (179 to 198),
                (199 to 213),
            ),
            spans(split(analysis, MULTI)),
        )
    }

    @Test
    fun `a piece starts after the previous one and a trailing incomplete one keeps its text`() {
        val pieces = split(analysis, MULTI)
        assertEquals(emptyList(), pieces.filter { it.start > it.codeStart })
        assertEquals(emptyList(), pieces.zipWithNext().filter { (a, b) -> b.start < a.end })
        // the block comment before `int b` belongs to its piece
        assertEquals(31, pieces.getOrNull(1)?.start)
        assertEquals("int broken = (", pieces.lastOrNull()?.source)
        assertEquals("int a = 1;", pieces.firstOrNull()?.source?.trim())
    }

    @Test
    fun `each line is read on its own, also after a leading line break`() {
        // the first line lacks its ';': read with the next line it would be no snippet
        assertEquals(
            listOf((1 to 10), (11 to 21)),
            spans(split(analysis, "\nint a = 1\nint b = 2;")),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["\u00A0\nint b = 2;\n", "int a = 1;\n\u00A0\nint b = 2;\n"])
    fun `a line of no-break spaces makes no piece that ends before it starts`(doc: String) {
        // JShell calls such a line a complete snippet of unknown kind
        val pieces = runCatching { split(analysis, doc) }
        assertNull(pieces.exceptionOrNull())
        val all = pieces.getOrDefault(emptyList())
        assertEquals(emptyList(), all.filter { it.codeStart < it.start || it.end < it.codeStart })
        val b = doc.indexOf("int b")
        assertEquals((b to b + 10), spans(all).lastOrNull())
    }

    @Test
    fun `blank lines and comments alone give no pieces`() {
        assertEquals(emptyList(), spans(split(analysis, "\n  \n// only a comment\n/* and a block */\n")))
        assertEquals(emptyList(), spans(split(analysis, "")))
    }

    @Test
    fun `offsets count UTF-16 units across an emoji`() {
        val doc = "String s = \"😀\";\nint n = s.length();\n"
        assertEquals(listOf((0 to 16), (17 to 36)), spans(split(analysis, doc)))
        assertEquals(1, lineOf(doc, 17))
    }

    @ParameterizedTest
    @CsvSource(
        "'   x', 0, 4, 3",
        "'// c\nx', 0, 6, 5",
        "'/* a */ /* b */x', 0, 16, 15",
        "'/* open', 0, 7, 7",
        "'// to the end', 0, 13, 13",
        "'x // c', 1, 6, 6",
        "'/* a */ x', 0, 5, 5",
        "'// c\nx', 0, 3, 3",
        "'ab', 1, 2, 1",
        // the '*' of the opening "/*" is no start of its close
        "'/*/ c */x', 0, 9, 8",
    )
    fun `code starts after blanks and comments within the limit`(
        doc: String,
        from: Int,
        limit: Int,
        expected: Int,
    ) {
        assertEquals(expected, codeStart(doc, from, limit))
    }

    @ParameterizedTest
    @CsvSource(
        // caret.jsonl: requests 2, 3, 4 and 5 (lines 10, 14, 18, 22) and the snippet each one ran
        "22, 22, '12-26'",
        "28, 28, '12-26'",
        "56, 56, '29-55'",
        "16, 40, '12-26 29-55'",
        // the caret at a snippet's first code character and at its end belongs to it
        "12, 12, '12-26'",
        "26, 26, '12-26'",
        "0, 56, '0-10 12-26 29-55'",
        // a selection that ends where the next snippet's code starts does not run it
        "0, 12, '0-10'",
        // a selection that starts where a snippet ends does not run it
        "10, 28, '12-26'",
        // a selection over blank lines only runs by its start, as a caret
        "27, 28, '12-26'",
    )
    fun `the caret or the selection picks the snippets`(
        rangeStart: Int,
        rangeEnd: Int,
        expected: String,
    ) {
        val picked = select(split(analysis, CARET), rangeStart, rangeEnd).map { "${it.codeStart}-${it.end}" }
        assertEquals(expected, picked.joinToString(" "))
    }

    @Test
    fun `a caret before the first snippet picks the first`() {
        val doc = "\n\n// c\nint a = 1;\nint b = 2;\n"
        assertEquals(listOf((7 to 17)), spans(select(split(analysis, doc), 1, 1)))
    }

    @Test
    fun `two snippets on one line run one at a time by the caret`() {
        val doc = "int a = 1; int b = 2;"
        val pieces = split(analysis, doc)
        assertEquals(listOf((0 to 10)), spans(select(pieces, 0, 0)))
        assertEquals(listOf((0 to 10)), spans(select(pieces, 10, 10)))
        assertEquals(listOf((11 to 21)), spans(select(pieces, 11, 11)))
    }

    @Test
    fun `nothing to select in an empty document`() {
        assertEquals(emptyList(), select(emptyList(), 0, 0))
    }

    @ParameterizedTest
    @CsvSource("0, 0", "1, 0", "2, 1", "4, 2", "5, 2", "99, 2")
    fun `the line of an offset counts the line breaks before it`(
        offset: Int,
        line: Int,
    ) {
        assertEquals(line, lineOf("a\nb\nc", offset))
    }
}
