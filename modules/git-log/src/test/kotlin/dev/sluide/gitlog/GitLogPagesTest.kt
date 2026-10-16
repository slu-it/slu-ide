package dev.sluide.gitlog

import dev.sluide.git.GitCommit
import dev.sluide.git.GitLogPage
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitLogPagesTest {
    private val repo = Path.of("/repo")
    private val other = Path.of("/other")

    /** The commits of each repository, newest first; missing means git fails. */
    private val history = mutableMapOf(repo to commits("r", 25), other to commits("o", 3))

    /** The (root, skip, limit) of every load. */
    private val loads = mutableListOf<Triple<Path, Int, Int>>()
    private val background = ArrayDeque<Runnable>()
    private val dispatched = ArrayDeque<Runnable>()

    private val pages =
        GitLogPages(
            load = { root, skip, limit ->
                loads += Triple(root, skip, limit)
                history[root]?.let { all ->
                    val page = all.drop(skip).take(limit)
                    GitLogPage(page, complete = skip + page.size >= all.size)
                }
            },
            background = background::addLast,
            dispatch = dispatched::addLast,
            pageSize = 10,
            prefetch = 3,
        )

    private fun commits(
        prefix: String,
        count: Int,
    ) = (1..count).map { GitCommit("$prefix$it", emptyList(), "A", "a@example.com", Instant.EPOCH, "Commit $it") }

    /** Runs the background work, then what it dispatched, as the executor and the JavaFX thread would. */
    private fun settle() {
        while (background.isNotEmpty() || dispatched.isNotEmpty()) {
            while (background.isNotEmpty()) background.removeFirst().run()
            while (dispatched.isNotEmpty()) dispatched.removeFirst().run()
        }
    }

    private fun hashes() = pages.rows.map { it.hash }

    @Test
    fun `loads nothing until visible, then the first page once`() {
        pages.open(repo)
        settle()
        assertEquals(emptyList(), loads)
        assertEquals(GitLogState.IDLE, pages.state.get())

        pages.visible = true
        assertEquals(GitLogState.LOADING, pages.state.get())
        settle()
        pages.visible = false
        pages.visible = true
        settle()

        assertEquals(listOf(Triple(repo, 0, 10)), loads)
        assertEquals((1..10).map { "r$it" }, hashes())
        assertEquals(GitLogState.LOADED, pages.state.get())
    }

    @Test
    fun `loads the next page near the end until a page is complete`() {
        pages.open(repo)
        pages.visible = true
        settle()

        pages.shown(6)
        settle()
        assertEquals(1, loads.size, "not near the end yet")

        pages.shown(7)
        pages.shown(8)
        settle()
        assertEquals(listOf(Triple(repo, 0, 10), Triple(repo, 10, 10)), loads, "one load while one is running")
        assertEquals(20, pages.rows.size)

        pages.shown(19)
        settle()
        assertEquals(25, pages.rows.size)
        pages.shown(24)
        settle()
        assertEquals(3, loads.size, "no more pages after a complete one")
        assertEquals((1..25).map { "r$it" }, hashes())
    }

    @Test
    fun `refreshing while hidden only marks the log stale`() {
        pages.open(repo)
        pages.visible = true
        settle()
        pages.visible = false

        pages.refresh()
        settle()
        assertEquals(1, loads.size)

        pages.visible = true
        settle()
        assertEquals(2, loads.size)
    }

    @Test
    fun `a reload replaces the rows and keeps the selection by hash`() {
        pages.open(repo)
        pages.visible = true
        settle()
        pages.shown(9)
        settle()
        val selected = mutableListOf<List<Int>>()
        pages.selectedHashes = { listOf("r3", "r15", "gone") }
        pages.select = { selected += it }
        history[repo] = commits("new", 1) + commits("r", 25)

        pages.refresh()
        settle()

        assertEquals(listOf("new1") + (1..9).map { "r$it" }, hashes())
        assertEquals(listOf(listOf(3)), selected, "r15 is no longer loaded")
    }

    @Test
    fun `an empty repository says so, a failure keeps the rows`() {
        history[other] = emptyList()
        pages.visible = true
        pages.open(other)
        settle()
        assertEquals(GitLogState.EMPTY, pages.state.get())
        assertTrue(pages.rows.isEmpty())

        pages.open(repo)
        settle()
        history.remove(repo)
        pages.refresh()
        settle()
        assertEquals(GitLogState.FAILED, pages.state.get())
        assertEquals(10, pages.rows.size)

        pages.shown(9)
        settle()
        assertEquals(3, loads.size, "no paging after a failure")
    }

    @Test
    fun `results for another folder or of a superseded run are dropped`() {
        pages.visible = true
        pages.open(repo)
        pages.open(other)
        settle()
        assertEquals(listOf("o1", "o2", "o3"), hashes())
        assertEquals(listOf(Triple(other, 0, 10)), loads, "the superseded run did not even start git")

        // A run that already started: its result comes after the folder changed.
        pages.open(repo)
        background.removeFirst().run()
        pages.open(other)
        settle()
        assertEquals(listOf("o1", "o2", "o3"), hashes())

        pages.open(null)
        settle()
        assertTrue(pages.rows.isEmpty())
        assertEquals(GitLogState.IDLE, pages.state.get())
    }

    @Test
    fun `a page loaded after a refresh started is dropped`() {
        pages.open(repo)
        pages.visible = true
        settle()
        pages.shown(9)
        background.removeFirst().run()
        pages.refresh()
        settle()

        assertEquals((1..10).map { "r$it" }, hashes())
    }
}
