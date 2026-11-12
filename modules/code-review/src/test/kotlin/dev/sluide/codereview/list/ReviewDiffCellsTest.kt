package dev.sluide.codereview.list

import dev.sluide.codereview.ReviewChange
import dev.sluide.codereview.model.ExpandDirection
import dev.sluide.codereview.model.NoticeKind
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.fileviewers.DiffKind
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewDiffCellsTest {
    @ParameterizedTest
    @CsvSource("1,5", "99999,5", "100000,6", "1234567,7")
    fun `a number column has room for five digits and grows for a longer number`(
        number: Int,
        digits: Int,
    ) {
        assertEquals(digits, numberColumnDigits(number))
    }

    @Test
    fun `a missing number needs five digits`() {
        assertEquals(5, numberColumnDigits(null))
    }

    @Test
    fun `the sign of a unified row follows the sides it has`() {
        fun sign(
            original: Int?,
            current: Int?,
        ) = unifiedSign(ReviewRow.UnifiedLine("a", original, current, DiffKind.MODIFIED))

        assertEquals("−", sign(3, null))
        assertEquals("+", sign(null, 3))
        assertEquals("", sign(3, 3))
    }

    @Test
    fun `notices say what is the matter`() {
        assertEquals("Loading…", noticeText(NoticeKind.LOADING, null))
        assertEquals("Binary file not shown", noticeText(NoticeKind.BINARY, null))
        assertEquals("File too large to show", noticeText(NoticeKind.TOO_LARGE, null))
        assertEquals("Submodule a1b2 → c3d4", noticeText(NoticeKind.SUBMODULE, "a1b2 → c3d4"))
        assertEquals("Renamed without changes", noticeText(NoticeKind.NO_CONTENT_CHANGE, "Renamed without changes"))
        assertEquals("Could not read this file", noticeText(NoticeKind.UNREADABLE, null))
        assertEquals("Load diff (1234 lines)", noticeText(NoticeKind.LOAD_DIFF, "1234"))
    }

    @Test
    fun `a long gap has the step buttons that apply and Expand all`() {
        val both = gapButtons(up = true, down = true, hidden = 88)
        assertEquals(listOf("↑ 20", "↓ 20", "Expand all (88)"), both.map { it.label })
        assertEquals(
            listOf(ExpandDirection.UP, ExpandDirection.DOWN, ExpandDirection.ALL),
            both.map { it.direction },
        )
        assertEquals("Show 20 more lines above", both[0].tooltip)
        assertEquals("Show 20 more lines below", both[1].tooltip)
        assertEquals(
            listOf("↓ 20", "Expand all (21)"),
            gapButtons(up = false, down = true, hidden = 21).map { it.label },
        )
    }

    @Test
    fun `a gap of at most 20 lines has one button for all of it`() {
        val buttons = gapButtons(up = true, down = true, hidden = 20)

        assertEquals(listOf("Expand 20 lines"), buttons.map { it.label })
        assertEquals(ExpandDirection.ALL, buttons.single().direction)
        assertEquals(listOf("Expand 1 line"), gapButtons(up = true, down = false, hidden = 1).map { it.label })
    }

    @Test
    fun `the path of a header is coloured by its change`() {
        assertEquals("review-change-added", changeStyleClass(ReviewChange.ADDED))
        assertEquals("review-change-deleted", changeStyleClass(ReviewChange.DELETED))
        assertEquals("review-change-modified", changeStyleClass(ReviewChange.RENAMED))
    }
}
