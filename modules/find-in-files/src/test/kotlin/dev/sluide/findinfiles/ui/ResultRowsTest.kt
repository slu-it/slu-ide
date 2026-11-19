package dev.sluide.findinfiles.ui

import dev.sluide.findinfiles.search.FileHits
import dev.sluide.findinfiles.search.LineHit
import dev.sluide.textediting.SearchOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class ResultRowsTest {
    private val root = Path.of("/work/project")

    private fun hit(line: Int) = LineHit(line, 0, "text", listOf(0 until 2))

    private fun hits(
        relative: String,
        lines: Int = 1,
        root: Path = this.root,
        count: Int = lines,
        capped: Boolean = false,
    ) = FileHits(root.resolve(relative), root, (0 until lines).map(::hit), count, capped)

    @Test
    fun `rows are a header then a row per line for each file`() {
        val rows = rowsOf(listOf(hits("a.kt", 2), hits("b.kt", 1)))
        assertEquals(
            listOf("H", "L", "L", "H", "L"),
            rows.map { if (it is ResultRow.Header) "H" else "L" },
        )
    }

    @Test
    fun `header shows name, folder relative to the root and count`() {
        assertEquals(HeaderParts("A.kt", "src/main", "(3)"), headerParts(hits("src/main/A.kt", 3), false))
        assertEquals(HeaderParts("A.kt", "", "(1)"), headerParts(hits("A.kt"), false))
    }

    @Test
    fun `a capped count has a plus`() {
        assertEquals("(100+)", headerParts(hits("A.kt", count = 100, capped = true), false).count)
    }

    @Test
    fun `several roots prefix the root's folder name`() {
        val scratches = Path.of("/home/me/scratches")
        assertEquals("scratches/notes", headerParts(hits("notes/n.md", root = scratches), true).folder)
        assertEquals("scratches", headerParts(hits("n.md", root = scratches), true).folder)
        assertEquals("project/src", headerParts(hits("src/A.kt"), true).folder)
    }

    @Test
    fun `a root without a name adds nothing to the folder`() {
        val top = Path.of("/")
        assertEquals("src", headerParts(hits("src/A.kt", root = top), true).folder)
        assertEquals("", headerParts(hits("A.kt", root = top), true).folder)
    }

    @ParameterizedTest
    @CsvSource("modules/file-explorer,file-explorer", "src,src", "a/b/,b", "a\\b,b")
    fun `the scope chip names the last folder`(
        label: String,
        expected: String,
    ) {
        assertEquals(expected, scopeName(label))
    }

    // rows: 0 H, 1 L, 2 L, 3 H, 4 L, 5 H, 6 L, 7 L
    private val rows = rowsOf(listOf(hits("a", 2), hits("b", 1), hits("c", 2)))

    @ParameterizedTest
    @CsvSource(
        "-1,1,1",
        "-1,-1,7",
        "1,1,2",
        "2,1,4",
        "4,1,6",
        "7,1,7",
        "4,-1,2",
        "1,-1,1",
        "2,-1,1",
    )
    fun `Up and Down move between line rows`(
        current: Int,
        delta: Int,
        expected: Int,
    ) {
        assertEquals(expected, moveLine(rows, current, delta))
    }

    @Test
    fun `no line rows select nothing`() {
        assertEquals(-1, moveLine(emptyList(), -1, 1))
        assertEquals(-1, pageLine(emptyList(), -1, 1, 5))
    }

    @ParameterizedTest
    @CsvSource(
        "-1,1,1",
        "-1,-1,7",
        "1,1,4",
        "2,1,4",
        "4,1,6",
        "7,1,7",
        "7,-1,4",
        "6,-1,4",
        "2,-1,1",
    )
    fun `PageDown and PageUp move by rows and land on a line row`(
        current: Int,
        direction: Int,
        expected: Int,
    ) {
        // A page of 2 rows; the line rows are 1, 2, 4, 6, 7.
        assertEquals(expected, pageLine(rows, current, direction, 2))
    }

    @Test
    fun `a header click goes to its first line row`() {
        assertEquals(1, lineRowFor(rows, 0))
        assertEquals(2, lineRowFor(rows, 2))
        assertEquals(-1, lineRowFor(rows, 99))
        assertEquals(-1, lineRowFor(listOf(ResultRow.Header(hits("a", 0))), 0))
    }

    @Test
    fun `preview target is the line start plus the matches on that line`() {
        val text = "one\ntwo foo\nthree foo foo\nfour"
        val target = checkNotNull(previewTarget(text, 2, "foo", SearchOptions()))
        val start = text.indexOf("three")
        assertEquals(
            listOf(start + 6 until start + 9, start + 10 until start + 13),
            target.matches.map {
                it.first until
                    it.last + 1
            },
        )
    }

    @Test
    fun `a changed file still highlights its line`() {
        val target = checkNotNull(previewTarget("x\nfoo", 1, "foo", SearchOptions()))
        assertEquals(listOf(2..4), target.matches)
    }

    @Test
    fun `a line deep in a file reveals the lines around it`() {
        val text = (0 until 12).joinToString("\n") { "a$it" }
        val target = checkNotNull(previewTarget(text, 6, "a6", SearchOptions()))

        assertEquals(listOf(18..19), target.matches)
        assertEquals(3, target.revealStart, "line 1, five above line 6")
        assertEquals(text.length, target.revealEnd, "the end of line 11, five below")
    }

    @Test
    fun `a line missing in a shorter file has no target`() {
        assertNull(previewTarget("one\ntwo", 5, "o", SearchOptions()))
        assertNull(previewTarget("one", -1, "o", SearchOptions()))
    }

    @Test
    fun `a line without the query has no matches but is revealed`() {
        val target = checkNotNull(previewTarget("a\nb\nc", 1, "zzz", SearchOptions()))
        assertEquals(emptyList(), target.matches)
        assertEquals(0, target.revealStart)
        assertEquals(5, target.revealEnd)
    }

    @Test
    fun `a snippet is cut at its highlights`() {
        assertEquals(
            listOf(
                SnippetSegment("a ", false),
                SnippetSegment("foo", true),
                SnippetSegment(" b ", false),
                SnippetSegment("o", true),
            ),
            snippetSegments("a foo b o", listOf(2 until 5, 8 until 9)),
        )
        assertEquals(listOf(SnippetSegment("abc", false)), snippetSegments("abc", emptyList()))
        assertEquals(listOf(SnippetSegment("ab", true)), snippetSegments("ab", listOf(0 until 10)))
        // Overlapping ranges do not repeat text; a range past the end is left out.
        assertEquals(
            listOf(SnippetSegment("abcde", true), SnippetSegment("fg", true), SnippetSegment("hij", false)),
            snippetSegments("abcdefghij", listOf(0 until 5, 3 until 7)),
        )
        assertEquals(listOf(SnippetSegment("abc", false)), snippetSegments("abc", listOf(20 until 22)))
        // Ranges in any order give the pieces in text order.
        assertEquals(
            listOf(
                SnippetSegment("a ", false),
                SnippetSegment("foo", true),
                SnippetSegment(" b ", false),
                SnippetSegment("o", true),
            ),
            snippetSegments("a foo b o", listOf(8 until 9, 2 until 5)),
        )
    }
}
