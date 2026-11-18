package dev.sluide.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitFetchResultTest {
    @Test
    fun `changes are none only when every count is zero`() {
        assertTrue(GitFetchChanges().none)
        assertFalse(GitFetchChanges(newBranches = 1).none)
        assertFalse(GitFetchChanges(updatedBranches = 1).none)
        assertFalse(GitFetchChanges(prunedBranches = 1).none)
        assertFalse(GitFetchChanges(newTags = 1).none)
        assertFalse(GitFetchChanges(updatedTags = 1).none)
        assertFalse(GitFetchChanges(prunedTags = 1).none)
    }

    @Test
    fun `changes add up count by count`() {
        val first = GitFetchChanges(1, 2, 3, 4, 5, 6)
        val second = GitFetchChanges(10, 20, 30, 40, 50, 60)

        assertEquals(GitFetchChanges(11, 22, 33, 44, 55, 66), first + second)
        assertEquals(first, first + GitFetchChanges())
    }
}
