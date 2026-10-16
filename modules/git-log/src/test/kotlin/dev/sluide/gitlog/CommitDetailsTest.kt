package dev.sluide.gitlog

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
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

    /** The (commit, first parent) of every load of changed files. */
    private val fileLoads = mutableListOf<Pair<String, String?>>()

    /** Hashes whose changed files cannot be read. */
    private val failingFiles = mutableSetOf<String>()

    /** Runs while details are read, e.g. to select another commit meanwhile. */
    private var whileLoading: () -> Unit = {}

    /** How many results were waiting for dispatch when each load of changed files started. */
    private val dispatchedBeforeFiles = mutableListOf<Int>()

    private fun files(hash: String) = listOf(GitChangedFile(GitChange.MODIFIED, "$hash.txt"))

    private var selected = emptyList<GitLogRow>()

    private val details =
        CommitDetails(
            load = { root, hash ->
                assertEquals(repo, root)
                loads += hash
                whileLoading()
                if (hash in failing) null else details(hash)
            },
            loadFiles = { root, commit ->
                assertEquals(repo, root)
                dispatchedBeforeFiles += dispatched.size
                fileLoads += commit.hash to commit.parents.firstOrNull()
                if (commit.hash in failingFiles) null else files(commit.hash)
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

    @Test
    fun `loads the changed files of a single selected commit with its details, against its first parent`() {
        assertEquals(ChangedFilesState.NoneSelected, details.files.get())
        val merge =
            GitLogRow(commit("m").copy(parents = listOf("p1", "p2")), GraphLayout().next("m", listOf("p1", "p2")))
        select(merge)
        runDispatched()
        assertEquals(ChangedFilesState.Loading(merge), details.files.get())
        settle()
        assertEquals(ChangedFilesState.Loaded(merge, files("m")), details.files.get())
        assertEquals(listOf<Pair<String, String?>>("m" to "p1"), fileLoads)

        select(merge, row("b"))
        settle()
        assertEquals(ChangedFilesState.SeveralSelected(2), details.files.get())
        select()
        settle()
        assertEquals(ChangedFilesState.NoneSelected, details.files.get())

        val root = row("r")
        select(root)
        settle()
        assertEquals(listOf("m" to "p1", "r" to null), fileLoads)
    }

    @Test
    fun `changed files fail on their own, and a superseded load of them is dropped`() {
        failingFiles += "a"
        val a = row("a")
        select(a)
        settle()
        assertEquals(ChangedFilesState.Failed(a), details.files.get())
        assertEquals(CommitDetailsState.Loaded(a, details("a")), details.state.get())

        val b = row("b")
        select(b)
        runDispatched()
        background.removeFirst().run()
        select(row("c"))
        runDispatched()
        assertEquals(ChangedFilesState.Loading(row("c")), details.files.get())
        settle()
        assertEquals(ChangedFilesState.Loaded(row("c"), files("c")), details.files.get())
    }

    @Test
    fun `the details are handed on before the changed files are read`() {
        val a = row("a")
        select(a)
        runDispatched()
        background.removeFirst().run()

        assertEquals(listOf(1), dispatchedBeforeFiles, "the details were dispatched first")
        dispatched.removeFirst().run()
        assertEquals(CommitDetailsState.Loaded(a, details("a")), details.state.get())
        assertEquals(ChangedFilesState.Loading(a), details.files.get())
        runDispatched()
        assertEquals(ChangedFilesState.Loaded(a, files("a")), details.files.get())
    }

    @Test
    fun `the changed files are not read once another commit is selected after the details`() {
        val a = row("a")
        select(a)
        runDispatched()
        whileLoading = {
            whileLoading = {}
            select(row("b"))
            runDispatched()
        }
        background.removeFirst().run()

        assertEquals(emptyList(), fileLoads, "a's files are no longer wanted")
        settle()
        assertEquals(listOf<Pair<String, String?>>("b" to null), fileLoads)
        assertEquals(ChangedFilesState.Loaded(row("b"), files("b")), details.files.get())
    }
}
