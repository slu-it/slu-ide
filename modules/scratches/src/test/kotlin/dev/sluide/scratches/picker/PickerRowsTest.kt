package dev.sluide.scratches.picker

import dev.sluide.scratches.picker.PickerRow.Entry
import dev.sluide.scratches.picker.PickerRow.Header
import kotlin.test.Test
import kotlin.test.assertEquals

class PickerRowsTest {
    /** `[A] a1 a2 [B] b1`, by the first letter as the header. */
    private val rows = pickerRows(listOf("a1", "a2", "b1")) { it.take(1).uppercase() }

    @Test
    fun `a header goes before each run of the same title, none without titles`() {
        assertEquals(listOf(Header("A"), Entry("a1"), Entry("a2"), Header("B"), Entry("b1")), rows)
        assertEquals(listOf(Entry("a1"), Entry("b1")), pickerRows(listOf("a1", "b1"), null))
        assertEquals(emptyList(), pickerRows(emptyList<String>()) { it })
    }

    @Test
    fun `a title that comes back gets a header again`() {
        assertEquals(
            listOf(Header("A"), Entry("a1"), Header("B"), Entry("b1"), Header("A"), Entry("a2")),
            pickerRows(listOf("a1", "b1", "a2")) { it.take(1).uppercase() },
        )
    }

    @Test
    fun `the first entry and the entry of an item skip headers`() {
        assertEquals(1, firstEntry(rows))
        assertEquals(-1, firstEntry(listOf(Header("A"))))
        assertEquals(4, entryIndex(rows, "b1"))
        assertEquals(1, entryIndex(rows, "missing"))
        assertEquals(1, entryIndex(rows, null))
        assertEquals(-1, entryIndex(emptyList(), "a1"))
    }

    @Test
    fun `next entry skips headers and stops at the ends`() {
        assertEquals(2, nextEntry(rows, 1, 1))
        assertEquals(4, nextEntry(rows, 2, 1), "over the header of B")
        assertEquals(2, nextEntry(rows, 4, -1), "back over it")
        assertEquals(4, nextEntry(rows, 4, 1), "last stays")
        assertEquals(1, nextEntry(rows, 1, -1), "first stays, the header above is no entry")
        assertEquals(1, nextEntry(rows, -1, 1), "nothing selected: down is the first entry")
        assertEquals(-1, nextEntry(emptyList<PickerRow<String>>(), -1, 1))
    }

    @Test
    fun `scrolling to the first entry of a group shows its header`() {
        assertEquals(0, scrollTarget(rows, 1))
        assertEquals(2, scrollTarget(rows, 2))
        assertEquals(3, scrollTarget(rows, 4))
        assertEquals(0, scrollTarget(pickerRows(listOf("a"), null), 0))
    }
}
