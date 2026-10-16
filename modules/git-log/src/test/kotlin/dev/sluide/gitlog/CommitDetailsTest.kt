package dev.sluide.gitlog

import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommitDetails
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CommitDetailsTest {
    private val repo = Path.of("/repo")
    private val background = ArrayDeque<Runnable>()
    private val dispatched = ArrayDeque<Runnable>()

    /** The hash of every load. */
    private val loads = mutableListOf<String>()

    /** Hashes whose details cannot be read. */
    private val failing = mutableSetOf<String>()

    private var selected = emptyList<GitLogRow>()

    private val details =
        CommitDetails(
            load = { root, hash ->
                assertEquals(repo, root)
                loads += hash
                if (hash in failing) null else details(hash)
            },
            background = background::addLast,
            dispatch = dispatched::addLast,
        ).apply {
            selection = { selected }
            root = { repo }
        }

    private fun commit(hash: String) =
        GitCommit(hash, emptyList(), "A", "a@example.com", Instant.EPOCH, "Subject $hash")

    private fun row(hash: String) = GitLogRow(commit(hash), GraphLayout().next(hash, emptyList()))

    private fun details(hash: String) =
        GitCommitDetails(commit(hash), "A", "a@example.com", Instant.EPOCH, "Subject $hash")

    private fun select(vararg rows: GitLogRow) {
        selected = rows.toList()
        details.selectionChanged()
    }

    private fun runDispatched() {
        while (dispatched.isNotEmpty()) dispatched.removeFirst().run()
    }

    private fun settle() {
        while (background.isNotEmpty() || dispatched.isNotEmpty()) {
            while (background.isNotEmpty()) background.removeFirst().run()
            runDispatched()
        }
    }

    @Test
    fun `loads the details of a single selected commit, says how many when several or none are`() {
        assertEquals(CommitDetailsState.NoneSelected, details.state.get())
        val a = row("a")
        select(a)
        runDispatched()
        assertEquals(CommitDetailsState.Loading(a), details.state.get())
        settle()
        assertEquals(CommitDetailsState.Loaded(a, details("a")), details.state.get())

        select(a, row("b"))
        settle()
        assertEquals(CommitDetailsState.SeveralSelected(2), details.state.get())
        select()
        settle()
        assertEquals(CommitDetailsState.NoneSelected, details.state.get())
        assertEquals(listOf("a"), loads)
    }

    @Test
    fun `looks at the selection once its changes are done`() {
        val a = row("a")
        select()
        select(a, row("b"))
        select(a)
        settle()

        assertEquals(listOf("a"), loads)
        assertEquals(CommitDetailsState.Loaded(a, details("a")), details.state.get())
    }

    @Test
    fun `a result for a commit no longer selected is dropped, the latest wins`() {
        val a = row("a")
        val b = row("b")
        select(a)
        runDispatched()
        // a's load runs, but its result comes after b was selected.
        background.removeFirst().run()
        select(b)
        runDispatched()
        settle()

        assertEquals(listOf("a", "b"), loads)
        assertEquals(CommitDetailsState.Loaded(b, details("b")), details.state.get())

        // A load that has not started yet when the selection moves on does not even run.
        select(a)
        runDispatched()
        select()
        runDispatched()
        settle()
        assertEquals(listOf("a", "b"), loads)
        assertEquals(CommitDetailsState.NoneSelected, details.state.get())
    }

    @Test
    fun `the same row selected again loads nothing, a new row of the same commit (a reload) loads again`() {
        val a = row("a")
        select(a)
        settle()
        select(a)
        settle()
        assertEquals(listOf("a"), loads)

        val reloaded = row("a")
        select(reloaded)
        settle()
        assertEquals(listOf("a", "a"), loads)
        assertSame(reloaded, (details.state.get() as CommitDetailsState.Loaded).row)
    }

    @Test
    fun `a failure says so, and nothing is handled after close`() {
        failing += "a"
        val a = row("a")
        select(a)
        settle()
        assertEquals(CommitDetailsState.Failed(a), details.state.get())

        val b = row("b")
        select(b)
        runDispatched()
        details.close()
        settle()
        assertEquals(CommitDetailsState.Loading(b), details.state.get())
        select(a)
        settle()
        assertEquals(listOf("a"), loads)
    }
}
