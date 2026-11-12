package dev.sluide.codereview

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReviewSelectionTest {
    private fun commit(
        hash: String,
        parent: String?,
    ) = ReviewCommit(hash, hash.take(7), "subject $hash", "author", Instant.EPOCH, parent)

    private val commits = listOf(commit("c3", "c2"), commit("c2", "c1"), commit("c1", null))

    private fun review(uncommitted: Boolean) = ResolvedReview("feature", null, "base", "c3", commits, uncommitted)

    @Test
    fun `all runs from the review's start to its head`() {
        assertEquals("base" to ReviewRevision.Commit("c3"), review(uncommitted = false).range(ReviewSelection.All))
    }

    @Test
    fun `all reaches the working tree when the review has uncommitted changes`() {
        assertEquals("base" to ReviewRevision.WorkingTree, review(uncommitted = true).range(ReviewSelection.All))
    }

    @Test
    fun `uncommitted runs from the head to the working tree`() {
        assertEquals("c3" to ReviewRevision.WorkingTree, review(uncommitted = true).range(ReviewSelection.Uncommitted))
    }

    @Test
    fun `one commit runs from its first parent`() {
        assertEquals(
            "c1" to ReviewRevision.Commit("c2"),
            review(uncommitted = false).range(ReviewSelection.Commits(newest = 1, oldest = 1)),
        )
    }

    @Test
    fun `a range runs from the oldest commit's first parent to the newest commit`() {
        assertEquals(
            "c1" to ReviewRevision.Commit("c3"),
            review(uncommitted = false).range(ReviewSelection.Commits(newest = 0, oldest = 1)),
        )
    }

    @Test
    fun `a root commit runs from the empty tree`() {
        assertEquals(
            null to ReviewRevision.Commit("c2"),
            review(uncommitted = false).range(ReviewSelection.Commits(newest = 1, oldest = 2)),
        )
    }

    @ParameterizedTest
    @CsvSource("-1, 0", "0, 3", "2, 1", "3, 3")
    fun `invalid indices are refused`(
        newest: Int,
        oldest: Int,
    ) {
        assertFailsWith<IllegalArgumentException> {
            review(uncommitted = false).range(ReviewSelection.Commits(newest, oldest))
        }
    }
}
