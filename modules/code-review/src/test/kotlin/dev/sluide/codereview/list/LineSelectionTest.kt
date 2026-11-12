package dev.sluide.codereview.list

import dev.sluide.codereview.model.ReviewDocument
import dev.sluide.codereview.model.ReviewLayout
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.ReviewSide
import dev.sluide.codereview.model.currentLine
import dev.sluide.codereview.model.document
import dev.sluide.codereview.model.oneModifiedLine
import dev.sluide.codereview.model.originalLine
import dev.sluide.codereview.model.textDiff
import dev.sluide.fileviewers.LineChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LineSelectionTest {
    /** a.txt: o2 and o3 replaced by c2 (one added line replaces two removed ones); b.txt: one modified line. */
    private fun twoFiles(layout: ReviewLayout): ReviewDocument =
        document(
            "a.txt" to textDiff(6, 5, LineChange(2..3, 2..2)),
            "b.txt" to oneModifiedLine(5, 2),
            layout = layout,
        )

    private fun ReviewDocument.indexOf(
        path: String,
        original: Int?,
        current: Int?,
    ) = rows.indexOfFirst {
        it.path == path && it.originalLine() == original && it.currentLine() == current
    }

    @Test
    fun `a click in the unified view selects the row with its current line, a removed row with its original one`() {
        val doc = twoFiles(ReviewLayout.UNIFIED)
        val added = doc.indexOf("a.txt", null, 2)
        val removed = doc.indexOf("a.txt", 2, null)

        val onAdded = assertNotNull(LineSelection.at(doc.rows, added, null))
        val onRemoved = assertNotNull(LineSelection.at(doc.rows, removed, null))

        assertEquals(listOf(added), onAdded.rows(doc.rows))
        assertEquals(listOf(removed), onRemoved.rows(doc.rows))
        assertEquals("c2", onAdded.text(doc.rows, doc))
        assertEquals("o2", onRemoved.text(doc.rows, doc))
    }

    @Test
    fun `shift click in the unified view extends over rows of mixed sides and copies each row's own line`() {
        val doc = twoFiles(ReviewLayout.UNIFIED)
        val first = doc.indexOf("a.txt", 2, null)
        val last = doc.indexOf("a.txt", null, 2)

        val selection = assertNotNull(LineSelection.at(doc.rows, first, null)?.extend(doc.rows, last, null))

        assertEquals((first..last).toList(), selection.rows(doc.rows))
        assertEquals("o2\no3\nc2", selection.text(doc.rows, doc))
    }

    @Test
    fun `shift click extends upwards from the first click`() {
        val doc = twoFiles(ReviewLayout.UNIFIED)
        val first = doc.indexOf("a.txt", 4, 3)
        val last = doc.indexOf("a.txt", 1, 1)

        val selection = assertNotNull(LineSelection.at(doc.rows, first, null)?.extend(doc.rows, last, null))

        assertEquals(listOf(last, last + 1, last + 2, last + 3), selection.rows(doc.rows).take(4))
        assertEquals(first, selection.rows(doc.rows).last())
    }

    @Test
    fun `a click or shift click in another file starts a new selection`() {
        val doc = twoFiles(ReviewLayout.UNIFIED)
        val a = doc.indexOf("a.txt", 1, 1)
        val b = doc.indexOf("b.txt", 3, 3)

        val extended = assertNotNull(LineSelection.at(doc.rows, a, null)?.extend(doc.rows, b, null))

        assertEquals("b.txt", extended.path)
        assertEquals(listOf(b), extended.rows(doc.rows))
    }

    @Test
    fun `in the split view a number selects its side and shift click skips the fillers`() {
        val doc = twoFiles(ReviewLayout.SPLIT)
        val rows = doc.rows
        val first = doc.indexOf("a.txt", 2, 2)
        val last = doc.indexOf("a.txt", 4, 3)
        // The two removed lines face one added line and a filler.
        val filler = doc.indexOf("a.txt", 3, null)
        assertEquals(first + 1, filler)

        val original =
            assertNotNull(LineSelection.at(rows, first, ReviewSide.ORIGINAL)?.extend(rows, last, ReviewSide.ORIGINAL))
        val current =
            assertNotNull(LineSelection.at(rows, first, ReviewSide.CURRENT)?.extend(rows, last, ReviewSide.CURRENT))

        assertEquals("o2\no3\no4", original.text(rows, doc))
        assertEquals(ReviewSide.ORIGINAL, original.side)
        assertEquals(listOf(first, first + 1, last), original.rows(rows).take(2) + original.rows(rows).last())
        assertEquals(listOf(first, last), current.rows(rows))
        assertEquals("c2\no4", current.text(rows, doc))
    }

    @Test
    fun `a click on a filler half selects nothing`() {
        val doc = twoFiles(ReviewLayout.SPLIT)
        val filler = doc.indexOf("a.txt", 3, null)

        assertNull(LineSelection.at(doc.rows, filler, ReviewSide.CURRENT))
        assertNull(LineSelection.at(doc.rows, 0, null))
    }

    @Test
    fun `a shift click on the other side starts a new selection`() {
        val doc = twoFiles(ReviewLayout.SPLIT)
        val row = doc.indexOf("a.txt", 2, 2)

        val next =
            assertNotNull(
                LineSelection.at(doc.rows, row, ReviewSide.ORIGINAL)?.extend(doc.rows, row, ReviewSide.CURRENT),
            )

        assertEquals(ReviewSide.CURRENT, next.side)
        assertEquals(listOf(row), next.rows(doc.rows))
    }

    @Test
    fun `the selection follows its lines when the layout changes and is gone with them`() {
        val doc = twoFiles(ReviewLayout.UNIFIED)
        val removed = doc.indexOf("a.txt", 2, null)
        val selection = assertNotNull(LineSelection.at(doc.rows, removed, null))

        doc.layout.set(ReviewLayout.SPLIT)
        val inSplit = selection.rows(doc.rows)

        assertEquals(1, inSplit.size)
        assertEquals(2, (doc.rows[inSplit.single()] as ReviewRow.SplitLine).original)
        // The split row pairs o2 with c2, but only the old line was selected: its text and its half.
        assertEquals("o2", selection.text(doc.rows, doc))
        assertEquals(listOf(ReviewSide.ORIGINAL), selection.sidesOf(doc.rows[inSplit.single()]))

        doc.layout.set(ReviewLayout.UNIFIED)
        val mixed = assertNotNull(selection.extend(doc.rows, doc.indexOf("a.txt", null, 2), null))
        doc.layout.set(ReviewLayout.SPLIT)
        assertEquals("o2\no3\nc2", mixed.text(doc.rows, doc))
        assertEquals(
            listOf(ReviewSide.ORIGINAL, ReviewSide.CURRENT),
            mixed.sidesOf(doc.rows[doc.indexOf("a.txt", 2, 2)]).sorted(),
        )
        doc.layout.set(ReviewLayout.UNIFIED)

        doc.setCollapsed("a.txt", true)
        assertEquals(emptyList(), selection.rows(doc.rows))
    }

    @Test
    fun `the line to open for a removed row is the next current line of the file, else the previous one`() {
        val doc = twoFiles(ReviewLayout.UNIFIED)
        val removed = doc.indexOf("a.txt", 2, null)
        val added = doc.indexOf("a.txt", null, 2)
        val lastRemoved = doc.indexOf("a.txt", 3, null)

        assertEquals(2, nearestCurrentLine(doc.rows, removed))
        assertEquals(2, nearestCurrentLine(doc.rows, lastRemoved))
        assertEquals(2, nearestCurrentLine(doc.rows, added))
        // A file of only removed lines has none; the header neither.
        val deleted = document("d.txt" to textDiff(2, 0, LineChange(0..1, 0..-1)), layout = ReviewLayout.UNIFIED)
        assertNull(nearestCurrentLine(deleted.rows, deleted.indexOf("d.txt", 0, null)))
        assertNull(nearestCurrentLine(doc.rows, doc.rows.size + 3))
    }

    @Test
    fun `F7 goes to the start of the next or previous block of changes`() {
        val doc = document("a.txt" to textDiff(30, 30, LineChange(2..3, 2..3), LineChange(20..20, 20..20)))
        val firstBlock = doc.indexOf("a.txt", 2, 2)
        val secondBlock = doc.indexOf("a.txt", 20, 20)

        assertEquals(firstBlock, nextChange(doc.rows, 0, true))
        // Inside a block the next one is the following block, not the next row of the same one.
        assertEquals(secondBlock, nextChange(doc.rows, firstBlock, true))
        assertEquals(secondBlock, nextChange(doc.rows, firstBlock + 1, true))
        assertNull(nextChange(doc.rows, secondBlock, true))
        assertEquals(firstBlock, nextChange(doc.rows, secondBlock, false))
        assertNull(nextChange(doc.rows, firstBlock, false))
    }

    @Test
    fun `Alt+Down and Alt+Up go to the next and previous file header`() {
        val doc = twoFiles(ReviewLayout.SPLIT)
        val headers = doc.rows.indices.filter { doc.rows[it] is ReviewRow.FileHeader }
        assertEquals(2, headers.size)

        assertEquals(headers[1], nextFileHeader(doc.rows, headers[0], true))
        assertEquals(headers[1], nextFileHeader(doc.rows, headers[0] + 2, true))
        assertNull(nextFileHeader(doc.rows, headers[1], true))
        assertEquals(headers[0], nextFileHeader(doc.rows, headers[1], false))
        assertEquals(headers[0], nextFileHeader(doc.rows, headers[0] + 2, false))
        assertNull(nextFileHeader(doc.rows, headers[0], false))
    }
}
