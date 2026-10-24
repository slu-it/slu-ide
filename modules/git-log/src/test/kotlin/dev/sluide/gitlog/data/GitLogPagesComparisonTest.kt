package dev.sluide.gitlog.data

import dev.sluide.git.GitComparisonCounts
import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class GitLogPagesComparisonTest : GitLogPagesFixture() {
    private val feature = GitRef("refs/heads/feature", GitRefKind.LOCAL_BRANCH, "r5")

    /** The refs of [repo] with `HEAD` on main at r1 and the given others. */
    private fun refsWith(
        vararg others: GitRef,
        current: String? = "refs/heads/main",
    ) = GitRefs(
        listOf(GitRef("HEAD", GitRefKind.HEAD, "r1"), GitRef("refs/heads/main", GitRefKind.LOCAL_BRANCH, "r1")) +
            others,
        current,
    )

    @Test
    fun `a comparison loads one side of the range with the filter on top, and its counts with every first page`() {
        refs[repo] = refsWith(feature)
        comparisonCounts[feature.name] = GitComparisonCounts(3, 5)
        pages.open(repo)
        pages.visible = true
        settle()
        pages.filter = GitLogFilter(text = "Commit", revisions = listOf("refs/heads/main"), users = listOf("A"))
        settle()
        filters.clear()

        pages.comparison = LogComparison(feature)
        settle()

        val limited = GitLogFilter(text = "Commit", users = listOf("A"))
        assertEquals(
            listOf(limited.copy(revisions = listOf(feature.name), excluded = listOf("HEAD"))),
            filters,
        )
        assertEquals(GitComparisonCounts(3, 5), pages.counts)
        assertEquals(listOf(feature.name), countLoads)
        assertEquals(
            listOf(GitLogFilter(text = "Commit", revisions = listOf("refs/heads/main"), users = listOf("A"))),
            countFilters,
            "counted with the filter, so that they match the list",
        )
        assertEquals(limited.copy(revisions = listOf(feature.name), excluded = listOf("HEAD")), pages.effectiveFilter)

        pages.comparison = LogComparison(feature, ComparisonSide.HEAD_ONLY)
        assertEquals(GitComparisonCounts(3, 5), pages.counts, "kept while the other side loads")
        settle()
        assertEquals(limited.copy(revisions = listOf("HEAD"), excluded = listOf(feature.name)), filters.last())
        pages.shown(9)
        settle()
        assertEquals(2, countLoads.size, "not with further pages")
        assertEquals(limited.copy(revisions = listOf("HEAD"), excluded = listOf(feature.name)), filters.last())

        // A changed filter applies on top.
        pages.filter = GitLogFilter(text = "Commit 1")
        settle()
        assertEquals(
            GitLogFilter(text = "Commit 1", revisions = listOf("HEAD"), excluded = listOf(feature.name)),
            filters.last(),
        )
        assertEquals(GitLogFilter(text = "Commit 1"), countFilters.last(), "counted again with it")

        pages.comparison = null
        settle()
        assertEquals(GitLogFilter(text = "Commit 1"), filters.last(), "the filter as it was")
        assertNull(pages.counts)
        assertEquals(3, countLoads.size)
    }

    @Test
    fun `a refresh reads the counts again, a failure forgets them`() {
        refs[repo] = refsWith(feature)
        comparisonCounts[feature.name] = GitComparisonCounts(1, 0)
        pages.open(repo)
        pages.visible = true
        pages.comparison = LogComparison(feature)
        settle()
        assertEquals(GitComparisonCounts(1, 0), pages.counts)

        comparisonCounts[feature.name] = GitComparisonCounts(1, 2)
        pages.refresh()
        settle()
        assertEquals(GitComparisonCounts(1, 2), pages.counts)

        comparisonCounts.remove(feature.name)
        pages.refresh()
        settle()
        assertNull(pages.counts)
        assertEquals(LogComparison(feature), pages.comparison)
    }

    @Test
    fun `a refresh without the compared ref, or with it checked out, ends the comparison and loads again`() {
        val ended = mutableListOf<Pair<LogComparison, ComparisonEnd>>()
        pages.comparisonEnded = { comparison, end ->
            ended += comparison to end
            assertEquals(GitLogState.LOADING, pages.state.get(), "told once the log loads again")
        }
        refs[repo] = refsWith(feature)
        pages.open(repo)
        pages.visible = true
        pages.filter = GitLogFilter(text = "Commit")
        pages.comparison = LogComparison(feature)
        settle()
        val rows = hashes()

        refs[repo] = refsWith()
        filters.clear()
        pages.refresh()
        settle()

        assertEquals(listOf(LogComparison(feature) to ComparisonEnd.GONE), ended)
        assertNull(pages.comparison)
        assertEquals(GitLogFilter(text = "Commit"), filters.last(), "loaded again with the filter alone")
        assertEquals(2, filters.size)
        assertEquals(rows, hashes())

        refs[repo] = refsWith(feature)
        pages.comparison = LogComparison(feature)
        settle()
        refs[repo] = refsWith(feature, current = feature.name)
        pages.refresh()
        settle()
        assertEquals(LogComparison(feature) to ComparisonEnd.CURRENT, ended.last())
        assertNull(pages.comparison)

        // A detached HEAD at the compared commit does not end it.
        refs[repo] = refsWith(feature, current = null)
        pages.comparison = LogComparison(feature)
        settle()
        assertEquals(2, ended.size)
        assertEquals(LogComparison(feature), pages.comparison)
    }

    @Test
    fun `while comparing there is no hash match, an empty side matches nothing, opening a folder ends it`() {
        refs[repo] = refsWith(feature)
        hashMatch = { history.getValue(repo).first() }
        pages.open(repo)
        pages.visible = true
        pages.comparison = LogComparison(feature)
        pages.filter = GitLogFilter(text = "abcd")
        settle()
        assertEquals(emptyList(), hashLoads)
        assertEquals(GitLogState.NO_MATCH, pages.state.get())

        pages.open(repo)
        assertNull(pages.comparison)
        assertEquals(GitLogFilter(), pages.effectiveFilter)
    }
}
