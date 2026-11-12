package dev.sluide.codereview.find

import dev.sluide.codereview.ReviewFileContent
import dev.sluide.codereview.model.ExpandDirection
import dev.sluide.codereview.model.ReviewDocument
import dev.sluide.codereview.model.ReviewLayout
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.ReviewSide
import dev.sluide.codereview.model.document
import dev.sluide.codereview.model.short
import dev.sluide.codereview.model.textDiff
import dev.sluide.fileviewers.LineChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The search over the review's rows and the find's current match. */
class ReviewFindTest {
    private val block = textDiff(20, 21, LineChange(5..6, 5..7))

    private val big = textDiff(200, 200, LineChange(10..10, 10..10), LineChange(150..150, 150..150))

    private fun one(
        original: String,
        current: String,
        layout: ReviewLayout = ReviewLayout.SPLIT,
    ) = document(
        "a.kt" to ReviewFileContent.Text("$original\n", "$current\n", listOf(LineChange(0..0, 0..0))),
        layout = layout,
    )

    private fun ReviewDocument.search(
        query: String,
        matchCase: Boolean = false,
    ) = searchRows(rows, this, query, matchCase)

    private fun ReviewDocument.describe(matches: List<FindMatch>) =
        matches.map { "${rows[it.row].short()} ${it.side.name.first()} ${it.columns}" }

    @Test
    fun `a split row searches its original half, then its current half`() {
        val document = document("a.kt" to block)

        assertEquals(
            listOf("S 5 5 M O 1..1", "S 5 5 M C 1..1"),
            document.describe(document.search("5").matches),
        )
    }

    @Test
    fun `a unified row searches its one line, the current one else the original`() {
        val document = document("a.kt" to block, layout = ReviewLayout.UNIFIED)

        assertEquals(
            listOf("U 5 . M O 1..1", "U . 5 M C 1..1"),
            document.describe(document.search("5").matches),
        )
        // A context row holds the same text on both sides: found once.
        assertEquals(1, document.search("o3").matches.size)
    }

    @Test
    fun `case is ignored unless Match Case`() {
        val document = one("Foo bar", "foo FOO")

        assertEquals(listOf(0..2, 0..2, 4..6), document.search("foo").matches.map { it.columns })
        assertEquals(listOf(0..2), document.search("foo", matchCase = true).matches.map { it.columns })
    }

    @Test
    fun `an occurrence overlapping the previous one is not counted`() {
        val document = one("aaaaa", "bbbbb")

        assertEquals(listOf(0..1, 2..3), document.search("aa").matches.map { it.columns })
    }

    @Test
    fun `an empty query finds nothing`() {
        assertTrue(document("a.kt" to block).search("").matches.isEmpty())
    }

    @Test
    fun `at most 10000 matches are found, and the result says there are more`() {
        val result = one("a".repeat(MAX_MATCHES + 50), "b").search("a")

        assertEquals(MAX_MATCHES, result.matches.size)
        assertTrue(result.truncated)
        assertTrue(!one("a".repeat(MAX_MATCHES), "b").search("a").truncated)
    }

    @Test
    fun `headers, gaps and hidden context are not searched`() {
        val document = document("a.kt" to block)

        assertTrue(document.search("a.kt").matches.isEmpty())
        // Lines 0 and 1 and 10..19 are hidden behind the gaps.
        assertTrue(document.search("o0").matches.isEmpty())
        assertTrue(document.search("o15").matches.isEmpty())
        document.expand("a.kt", 1, ExpandDirection.ALL)
        assertEquals(2, document.search("o15").matches.size)
    }

    @Test
    fun `a collapsed file is not searched`() {
        val document = document("a.kt" to block, "b.kt" to block)
        assertEquals(4, document.search("5").matches.size)

        document.setCollapsed("a.kt", true)

        assertEquals(
            setOf("b.kt"),
            document
                .search("5")
                .matches
                .map { document.rows[it.row].path }
                .toSet(),
        )
    }

    @Test
    fun `searching makes the first match at or after a row current, else the first`() {
        val document = document("a.kt" to block)
        val find = ReviewFind(document).apply { query = "o" }

        find.search(fromRow = 0)
        assertEquals(0, find.current)

        val row = document.rows.indexOfFirst { it is ReviewRow.SplitLine && it.current == 6 }
        find.search(fromRow = row)
        assertEquals(row, find.matches[checkNotNull(find.current)].row)

        find.search(fromRow = document.rows.size)
        assertEquals(0, find.current)
    }

    @Test
    fun `next and previous wrap around`() {
        val find = ReviewFind(document("a.kt" to block)).apply { query = "5" }
        find.search(0)
        assertEquals("1 of 2", find.counterText())

        assertEquals(ReviewSide.CURRENT, find.next()?.side)
        assertEquals("2 of 2", find.counterText())
        assertEquals(ReviewSide.ORIGINAL, find.next()?.side)
        assertEquals(ReviewSide.CURRENT, find.previous()?.side)
        assertEquals(ReviewSide.ORIGINAL, find.previous()?.side)
        assertEquals(ReviewSide.CURRENT, find.previous()?.side)
    }

    @Test
    fun `without matches next is null and the counter reads 0 of 0, empty without a query`() {
        val find = ReviewFind(document("a.kt" to block))
        assertEquals("", find.counterText())

        find.query = "zzz"
        find.search(0)

        assertNull(find.next())
        assertNull(find.current)
        assertEquals("0 of 0", find.counterText())
    }

    @Test
    fun `the counter names the cap`() {
        val find = ReviewFind(one("a".repeat(MAX_MATCHES + 5), "b")).apply { query = "a" }
        find.search(0)

        assertEquals("1 of 10000+", find.counterText())
    }

    @Test
    fun `the current match stays on its line and column when rows are added above it`() {
        val document = document("a.kt" to block)
        val find = ReviewFind(document).apply { query = "5" }
        find.search(0)
        find.next()
        val before = find.matches[checkNotNull(find.current)]

        document.expand("a.kt", 0, ExpandDirection.ALL)
        find.rowsChanged(0)

        val after = find.matches[checkNotNull(find.current)]
        assertEquals(before.row + 1, after.row)
        assertEquals(before.side, after.side)
        assertEquals(before.columns, after.columns)
    }

    @Test
    fun `when the current match is gone the next one after it becomes current`() {
        val document = document("a.kt" to block, "b.kt" to block)
        val find = ReviewFind(document).apply { query = "5" }
        find.search(0)

        document.setCollapsed("a.kt", true)
        find.rowsChanged(0)

        assertEquals("b.kt", document.rows[find.matches[checkNotNull(find.current)].row].path)
        assertEquals("1 of 2", find.counterText())
    }

    @Test
    fun `the current match keeps its place when matches are inserted before it`() {
        val document = document("a.kt" to big)
        // "o14" is found in lines 147..149 below the last change; 14 and 140..146 are hidden behind the gap.
        val find = ReviewFind(document).apply { query = "o14" }
        find.search(0)
        val before = find.matches[checkNotNull(find.current)]
        val beforeRow = document.rows[before.row]
        assertEquals(0, find.current)

        document.expand("a.kt", 1, ExpandDirection.ALL)
        find.rowsChanged(0)

        val after = find.matches[checkNotNull(find.current)]
        assertEquals(16, find.current)
        assertEquals(beforeRow, document.rows[after.row])
        assertEquals(before.side, after.side)
    }

    @Test
    fun `a current match on the original half of a modified pair stays there when the layout changes`() {
        val document = document("a.kt" to block)
        val find = ReviewFind(document).apply { query = "5" }
        find.search(0)
        assertEquals(ReviewSide.ORIGINAL, find.matches[checkNotNull(find.current)].side)

        document.layout.set(ReviewLayout.UNIFIED)
        find.rowsChanged(0)

        val now = find.matches[checkNotNull(find.current)]
        assertEquals(ReviewSide.ORIGINAL, now.side)
        assertEquals("U 5 . M", document.rows[now.row].short())

        find.next()
        document.layout.set(ReviewLayout.SPLIT)
        find.rowsChanged(0)

        assertEquals(ReviewSide.CURRENT, find.matches[checkNotNull(find.current)].side)
    }

    @Test
    fun `when the current match's file is gone the first match of a later file becomes current`() {
        val document = document("a.kt" to block, "b.kt" to block, "c.kt" to block)
        val find = ReviewFind(document).apply { query = "5" }
        find.search(0)
        find.next()
        find.next()
        find.next()
        assertEquals("b.kt", document.rows[find.matches[checkNotNull(find.current)].row].path)

        document.setFiles(document.files.filter { it.path != "b.kt" })
        find.rowsChanged(0)

        assertEquals("c.kt", document.rows[find.matches[checkNotNull(find.current)].row].path)
        assertEquals(2, find.current)
    }

    @Test
    fun `without a current match before, the first match at or after the row becomes current`() {
        val document = document("a.kt" to big)
        val find = ReviewFind(document).apply { query = "o140" }
        find.search(0)
        assertNull(find.current)

        document.expand("a.kt", 1, ExpandDirection.ALL)
        val row = document.rows.indexOfFirst { it is ReviewRow.SplitLine && it.current == 140 }
        find.rowsChanged(row)

        assertEquals(row, find.matches[checkNotNull(find.current)].row)
    }
}
