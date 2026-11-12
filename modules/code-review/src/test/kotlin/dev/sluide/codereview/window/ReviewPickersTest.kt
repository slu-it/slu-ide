package dev.sluide.codereview.window

import dev.sluide.codereview.ResolvedReview
import dev.sluide.codereview.ReviewBase
import dev.sluide.codereview.ReviewChange
import dev.sluide.codereview.ReviewCommit
import dev.sluide.codereview.ReviewFile
import dev.sluide.codereview.model.ReviewSelection
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewPickersTest {
    private val now = Instant.parse("2026-11-12T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun commit(n: Int) =
        ReviewCommit("hash$n", "abc123$n", "Subject $n", "Ann", now.minusSeconds(3 * 86_400), null)

    private val review =
        ResolvedReview(
            "main..feature",
            ReviewBase("refs/heads/main", "main", true),
            "c0",
            "hash0",
            listOf(commit(0), commit(1), commit(2)),
            uncommitted = false,
        )

    @ParameterizedTest
    @CsvSource(
        "0,just now",
        "59,just now",
        "60,1 minute ago",
        "300,5 minutes ago",
        "3600,1 hour ago",
        "7200,2 hours ago",
        "86399,23 hours ago",
        "86400,yesterday",
        "172799,yesterday",
        "172800,2 days ago",
        "2592000,30 days ago",
        "2678400,2026-10-12",
        "-100,just now",
    )
    fun `the relative date`(
        seconds: Long,
        expected: String,
    ) {
        assertEquals(expected, relativeDate(now.minusSeconds(seconds), clock))
    }

    @Test
    fun `the button label per selection`() {
        assertEquals("All changes", selectionLabel(review, ReviewSelection.All))
        assertEquals("Uncommitted changes", selectionLabel(review, ReviewSelection.Uncommitted))
        assertEquals("abc1231 Subject 1", selectionLabel(review, ReviewSelection.Commits(1, 1)))
        assertEquals("abc1232..abc1230 (3 commits)", selectionLabel(review, ReviewSelection.Commits(0, 2)))
        assertEquals("All changes", selectionLabel(review, ReviewSelection.Commits(0, 9)))
    }

    @Test
    fun `the entries list all changes, uncommitted only when there are, then the commits newest first`() {
        val plain = pickerEntries(review, clock)

        assertEquals(
            listOf(
                "All changes (3 commits)",
                "abc1230  Subject 0 — Ann, 3 days ago",
                "abc1231  Subject 1 — Ann, 3 days ago",
                "abc1232  Subject 2 — Ann, 3 days ago",
            ),
            plain.map { it.text },
        )
        assertEquals(listOf(null, 0, 1, 2), plain.map { it.commitIndex })

        val dirty = pickerEntries(review.copy(uncommitted = true, commits = listOf(commit(0))), clock)

        assertEquals("All changes (1 commit + uncommitted)", dirty[0].text)
        assertEquals(ReviewSelection.Uncommitted, dirty[1].selection)
        assertEquals(ReviewSelection.Commits(0, 0), dirty[2].selection)
    }

    @Test
    fun `a range runs from the anchor to the target whichever is newer`() {
        assertEquals(ReviewSelection.Commits(1, 3), commitRange(1, 3))
        assertEquals(ReviewSelection.Commits(1, 3), commitRange(3, 1))
        assertEquals(ReviewSelection.Commits(2, 2), commitRange(2, 2))
        assertEquals(2, anchorOf(ReviewSelection.Commits(2, 4)))
        assertNull(anchorOf(ReviewSelection.All))
    }

    @Test
    fun `the base groups and the button text`() {
        val a = ReviewBase("refs/heads/main", "main", true)
        val b = ReviewBase("refs/heads/x", "x", false)
        val c = ReviewBase("refs/remotes/origin/main", "origin/main", true)

        assertEquals(listOf(a, c) to listOf(b), baseGroups(listOf(a, b, c)))
        assertEquals("into: main", baseButtonText(review))
        assertEquals("choose a base", baseButtonText(review.copy(base = null)))
        assertEquals("choose a base", baseButtonText(null))
    }

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
