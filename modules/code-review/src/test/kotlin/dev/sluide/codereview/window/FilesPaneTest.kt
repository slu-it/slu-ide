package dev.sluide.codereview.window

import dev.sluide.codereview.ReviewChange
import dev.sluide.codereview.ReviewFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilesPaneTest {
    @Test
    fun `the tree names are coloured by change and renames carry a tooltip`() {
        assertEquals("review-change-added", nameStyleClass(ReviewChange.ADDED))
        assertEquals("review-change-deleted", nameStyleClass(ReviewChange.DELETED))
        assertEquals("review-change-modified", nameStyleClass(ReviewChange.RENAMED))
        assertEquals("review-change-modified", nameStyleClass(ReviewChange.COPIED))
        assertEquals("Renamed from a.kt", changeTooltip(ReviewFile(ReviewChange.RENAMED, "b.kt", "a.kt")))
        assertEquals("Copied from a.kt", changeTooltip(ReviewFile(ReviewChange.COPIED, "b.kt", "a.kt")))
        assertNull(changeTooltip(ReviewFile(ReviewChange.MODIFIED, "b.kt")))
    }

    @Test
    fun `the tree scrolls only when the row is out of view`() {
        assertNull(treeScrollTarget(5, 0, 10))
        assertNull(treeScrollTarget(10, 0, 10))
        assertEquals(28, treeScrollTarget(30, 0, 10))
        assertEquals(0, treeScrollTarget(1, 5, 10))
    }
}
