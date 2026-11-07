package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.TextChange
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * How following overlay ranges, caret and selection move with edits, and which edits `applyEdits` takes (ADR 0141);
 * that the code view applies them is `ExtensibleCodeEditorApiUiTest`'s.
 */
class EditOffsetsTest {
    /** A change at [start] replacing [removed] characters by [inserted] ones. */
    private fun change(
        start: Int,
        removed: Int,
        inserted: Int,
    ) = TextChange(start, "r".repeat(removed), "i".repeat(inserted))

    @ParameterizedTest(name = "[{0}, {1}) after replacing {3} at {2} by {4}: {5}")
    @CsvSource(
        // Entirely before: stays.
        "10, 14, 20, 2, 5, 10-14",
        "10, 14, 15, 0, 3, 10-14",
        // Entirely after: moves by the change in length.
        "10, 14, 2, 3, 1, 8-12",
        "10, 14, 2, 0, 4, 14-18",
        "10, 14, 5, 4, 0, 6-10",
        // Overlapping: dropped.
        "10, 14, 12, 0, 1, dropped",
        "10, 14, 8, 4, 1, dropped",
        "10, 14, 13, 5, 0, dropped",
        "10, 14, 9, 8, 2, dropped",
        // Touching: dropped too, at either end.
        "10, 14, 14, 0, 2, dropped",
        "10, 14, 14, 3, 0, dropped",
        "10, 14, 10, 0, 2, dropped",
        "10, 14, 7, 3, 3, dropped",
    )
    fun `a following range stays before an edit, moves after it and is dropped where it overlaps or touches it`(
        start: Int,
        end: Int,
        at: Int,
        removed: Int,
        inserted: Int,
        expected: String,
    ) {
        val followed = followRange(StyledRange(start, end, "diagnostic"), change(at, removed, inserted))

        if (expected == "dropped") {
            assertNull(followed)
        } else {
            val (from, to) = expected.split('-').map(String::toInt)
            assertEquals(StyledRange(from, to, "diagnostic"), followed)
        }
    }

    @Test
    fun `an offset to restyle stays before a change, moves after it and goes to its start inside it`() {
        val change = change(10, 4, 1)

        assertEquals(listOf(3, 10, 10, 10, 11, 16), listOf(3, 10, 12, 13, 14, 19).map { offsetAfter(it, change) })
    }

    @Test
    fun `a batch of changes drops, moves and clears layers, and restyles where dropped classes went`() {
        val layers =
            linkedMapOf(
                "diagnostics" to
                    LayerRanges(listOf(StyledRange(10, 14, "error"), StyledRange(20, 24, "warning")), following = true),
                "highlights" to LayerRanges(listOf(StyledRange(30, 32, "usage")), following = true),
            )
        // Inside the error, then before everything, as the changes of one multi-change come.
        val changes = listOf(change(12, 0, 1), change(2, 0, 3))

        val followed = followChanges(layers, changes)

        assertEquals(
            mapOf(
                "diagnostics" to LayerRanges(listOf(StyledRange(24, 28, "warning")), following = true),
                "highlights" to LayerRanges(listOf(StyledRange(34, 36, "usage")), following = true),
            ),
            followed.layers,
        )
        // The error's classes went to [10, 15) with the first change, which the second moves to [13, 18); the hull with
        // the second's inserted [2, 5).
        assertEquals(2 until 18, followed.restyle)
    }

    @Test
    fun `a layer that does not follow edits is cleared and goes, a deletion away from ranges restyles nothing`() {
        val kept = mapOf("diagnostics" to LayerRanges(listOf(StyledRange(4, 6, "error")), following = true))
        val layers = linkedMapOf("sentence" to LayerRanges(listOf(StyledRange(0, 9, "sentence")), following = false))
        layers.putAll(kept)

        val cleared = followChanges(layers, listOf(change(20, 2, 0)))

        assertEquals(kept, cleared.layers)
        assertEquals(0 until 20, cleared.restyle, "from where the sentence was to the deletion")
        assertEquals(FollowedLayers(kept, null), followChanges(kept, listOf(change(20, 2, 0))))
    }

    @Test
    fun `edits are sorted by range, inserts at the same offset keep their order`() {
        val first = OffsetEdit(5, 5, "a")
        val second = OffsetEdit(5, 5, "b")
        val replacing = OffsetEdit(5, 7, "c")
        val earlier = OffsetEdit(0, 2, "")

        assertEquals(
            listOf(earlier, first, second, replacing),
            sortedEdits(listOf(replacing, first, earlier, second), length = 10),
        )
        assertEquals(emptyList(), sortedEdits(emptyList(), length = 0))
        assertEquals(
            listOf(OffsetEdit(0, 3, "")),
            sortedEdits(listOf(OffsetEdit(0, 3, "")), length = 3),
            "the whole text",
        )
    }

    @Test
    fun `edits outside the text, reversed or overlapping are refused`() {
        assertFailsWith<IllegalArgumentException> { sortedEdits(listOf(OffsetEdit(2, 11, "")), length = 10) }
        assertFailsWith<IllegalArgumentException> { sortedEdits(listOf(OffsetEdit(-1, 0, "")), length = 10) }
        assertFailsWith<IllegalArgumentException> { sortedEdits(listOf(OffsetEdit(4, 3, "")), length = 10) }
        assertFailsWith<IllegalArgumentException> {
            sortedEdits(listOf(OffsetEdit(5, 8, "x"), OffsetEdit(2, 6, "y")), length = 10)
        }
        assertFailsWith<IllegalArgumentException> {
            sortedEdits(listOf(OffsetEdit(5, 6, "x"), OffsetEdit(5, 7, "y")), length = 10)
        }
    }

    @ParameterizedTest(name = "caret {0}: {1}")
    @CsvSource(
        // Before every edit: stays.
        "1, 1",
        // At the start of the replaced range: stays before it.
        "2, 2",
        // Inside the replaced range: after its new text.
        "3, 7",
        // At the end of the replaced range, or after it: shifted by its change in length (+3).
        "4, 7",
        "5, 8",
        // At the insert: after the inserted text (+3 +2).
        "8, 13",
        // After both.
        "9, 14",
    )
    fun `a caret moves with the edits before it and goes after an edit containing it`(
        caret: Int,
        expected: Int,
    ) {
        val edits = sortedEdits(listOf(OffsetEdit(8, 8, "xy"), OffsetEdit(2, 4, "abcde")), length = 10)

        assertEquals(expected, offsetAfterEdits(caret, edits))
    }
}
