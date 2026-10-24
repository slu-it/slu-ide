package dev.sluide.gitlog.area

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.gitlog.data.CommitMissing
import dev.sluide.gitlog.data.ComparisonEnd
import dev.sluide.gitlog.data.LogComparison
import kotlin.test.Test
import kotlin.test.assertEquals

class AreaMessagesTest {
    private fun missing(
        missing: CommitMissing,
        comparing: Boolean = false,
        filterIsDefault: Boolean = true,
    ) = missingMessage("feature", missing, comparing, filterIsDefault)

    @Test
    fun `an unreadable log says so, whatever else is going on`() {
        assertEquals(
            "The git log could not be read.",
            missing(CommitMissing.UNREADABLE, comparing = true, filterIsDefault = false),
        )
    }

    @Test
    fun `a commit beyond the limit says how far down the log it is looked for`() {
        assertEquals(
            "feature is more than 10,000 commits down.",
            missing(CommitMissing.BEYOND_LIMIT, comparing = true, filterIsDefault = false),
        )
    }

    @Test
    fun `a commit not in the log is not in the comparison while comparing, else hidden by filters or just not there`() {
        assertEquals("feature is not in this comparison.", missing(CommitMissing.NOT_IN_LOG, comparing = true))
        assertEquals(
            "feature is not in this comparison.",
            missing(CommitMissing.NOT_IN_LOG, comparing = true, filterIsDefault = false),
        )
        assertEquals("feature is hidden by the filters.", missing(CommitMissing.NOT_IN_LOG, filterIsDefault = false))
        assertEquals("feature is not in the log.", missing(CommitMissing.NOT_IN_LOG))
    }

    @Test
    fun `the end of a comparison says what became of the compared ref`() {
        val comparison = LogComparison(GitRef("refs/heads/feature", GitRefKind.LOCAL_BRANCH, "h1"))
        assertEquals("feature no longer exists.", endMessage(comparison, ComparisonEnd.GONE))
        assertEquals("feature is the current branch now.", endMessage(comparison, ComparisonEnd.CURRENT))
    }
}
