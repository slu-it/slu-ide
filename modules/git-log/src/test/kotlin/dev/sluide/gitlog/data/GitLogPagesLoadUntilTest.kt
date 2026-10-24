package dev.sluide.gitlog.data

import dev.sluide.git.GitLogFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class GitLogPagesLoadUntilTest : GitLogPagesFixture() {
    @Test
    fun `loadUntil finds a loaded commit at once and loads pages for a later one`() {
        pages.open(repo)
        pages.visible = true
        settle()

        loadUntil("r4")
        assertEquals(listOf<Any>(3), seeks)

        loadUntil("r15")
        settle()
        assertEquals(listOf<Any>(3, 14), seeks)
        assertEquals(listOf(Triple(repo, 0, 10), Triple(repo, 10, 10)), loads)
        assertEquals(20, pages.rows.size)
    }

    @Test
    fun `a reload stops the git of a page loading for loadUntil, and the seek goes on in the new rows`() {
        pages.open(repo)
        pages.visible = true
        settle()
        var reloads = 1
        whileLoading = { if (reloads-- > 0) pages.refresh() }

        loadUntil("r15")
        settle()

        assertEquals(listOf(false, true, false, false), loadCancellations.map { it.cancelled })
        assertEquals(listOf<Any>(14), seeks)
    }

    @Test
    fun `loadUntil waits for the first page`() {
        pages.open(repo)
        pages.visible = true

        loadUntil("r2")
        assertEquals(emptyList(), seeks)
        settle()

        assertEquals(listOf<Any>(1), seeks)
    }

    @Test
    fun `loadUntil says when the complete log does not have the commit, or the limit is reached`() {
        history[repo] = commits("r", 15)
        pages.open(repo)
        pages.visible = true
        settle()

        loadUntil("gone")
        settle()
        assertEquals(listOf<Any>(CommitMissing.NOT_IN_LOG), seeks)
        assertEquals(15, pages.rows.size)

        history[repo] = commits("r", 25)
        pages.refresh()
        settle()
        seeks.clear()
        loadUntil("r23")
        settle()
        assertEquals(listOf<Any>(CommitMissing.BEYOND_LIMIT), seeks)
        assertEquals(20, pages.rows.size, "no page beyond the limit")
    }

    @Test
    fun `loadUntil goes on after a refresh, in the new rows`() {
        pages.open(repo)
        pages.visible = true
        settle()

        loadUntil("r15")
        assertEquals("r15", pages.loadingUntil)
        pages.refresh()
        history[repo] = commits("new", 2) + commits("r", 25)
        settle()
        assertNull(pages.loadingUntil)

        assertEquals(listOf<Any>(16), seeks)
        // The page asked for before the refresh is dropped unread.
        assertEquals(listOf(Triple(repo, 0, 10), Triple(repo, 0, 10), Triple(repo, 10, 10)), loads)
    }

    @Test
    fun `loadUntil ends without telling on open, a filter change or a newer call, and tells a failure`() {
        pages.open(repo)
        pages.visible = true
        settle()

        loadUntil("r15")
        pages.filter = GitLogFilter(text = "Commit")
        settle()
        assertEquals(emptyList(), seeks, "a filter change")

        loadUntil("r15")
        pages.open(repo)
        settle()
        assertEquals(emptyList(), seeks, "open")

        loadUntil("r15")
        loadUntil("r12")
        settle()
        assertEquals(listOf<Any>(11), seeks, "replaced")

        seeks.clear()
        pages.open(repo)
        settle()
        loadUntil("r15")
        pages.stopLoadingUntil()
        settle()
        assertEquals(emptyList(), seeks, "stopped")
        assertEquals(20, pages.rows.size, "the page asked for still comes")

        pages.open(repo)
        settle()
        history.remove(repo)
        loadUntil("r15")
        settle()
        assertEquals(GitLogState.FAILED, pages.state.get())
        assertEquals(listOf<Any>(CommitMissing.UNREADABLE), seeks, "a failure of the next page")

        // The rows kept after a failure are still found; anything else cannot be looked for.
        seeks.clear()
        loadUntil("r3")
        loadUntil("r15")
        assertEquals(listOf<Any>(2, CommitMissing.UNREADABLE), seeks)

        // A failed first page.
        seeks.clear()
        pages.open(repo)
        loadUntil("r3")
        settle()
        assertEquals(listOf<Any>(CommitMissing.UNREADABLE), seeks, "a failure of the first page")
    }

    @Test
    fun `loadUntil while a page loads waits for it`() {
        pages.open(repo)
        pages.visible = true
        settle()

        pages.shown(9)
        loadUntil("r12")
        settle()

        assertEquals(listOf<Any>(11), seeks)
        assertEquals(2, loads.size)
    }
}
