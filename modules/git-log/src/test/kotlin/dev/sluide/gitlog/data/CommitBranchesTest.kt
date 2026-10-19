package dev.sluide.gitlog.data

import dev.sluide.git.GitCancellation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CommitBranchesTest {
    private val repo = Path.of("/repo")
    private val background = ArrayDeque<Runnable>()
    private val dispatched = ArrayDeque<Runnable>()

    /** The hash of every load. */
    private val loads = mutableListOf<String>()

    /** The cancellation of every load, which stops its git. */
    private val cancellations = mutableListOf<GitCancellation>()

    /** Runs while branches are read, e.g. to show another commit meanwhile. */
    private var whileLoading: () -> Unit = {}

    /** The refnames each commit is in; a missing commit fails. */
    private var refnames = mapOf("a" to listOf("refs/heads/main"), "b" to listOf("refs/heads/b", "refs/heads/main"))

    private val branches =
        CommitBranches(
            { root, hash, cancellation ->
                assertEquals(repo, root)
                loads += hash
                cancellations += cancellation
                whileLoading()
                // As git would answer once stopped.
                if (cancellation.cancelled) null else refnames[hash]
            },
            background::addLast,
            dispatched::addLast,
        ).apply { currentBranch = { "refs/heads/main" } }

    private fun runAll() {
        while (background.isNotEmpty() || dispatched.isNotEmpty()) {
            background.removeFirstOrNull()?.run()
            dispatched.removeFirstOrNull()?.run()
        }
    }

    @Test
    fun `reads the branches of the shown commit once and keeps them until the refs change`() {
        branches.show(repo, "a")
        assertEquals(BranchesState.Reading("a"), branches.state.get())
        runAll()
        assertEquals(BranchesState.Read("a", listOf("main")), branches.state.get())

        branches.show(repo, "b")
        runAll()
        assertEquals(BranchesState.Read("b", listOf("main", "b")), branches.state.get(), "the current branch first")

        branches.show(repo, "a")
        assertEquals(BranchesState.Read("a", listOf("main")), branches.state.get(), "kept, at once")
        assertEquals(listOf("a", "b"), loads)

        // New refs: read again, the old ones shown meanwhile.
        refnames = mapOf("a" to listOf("refs/heads/main", "refs/heads/new"))
        branches.refsChanged()
        assertEquals(BranchesState.Read("a", listOf("main")), branches.state.get())
        runAll()
        assertEquals(BranchesState.Read("a", listOf("main", "new")), branches.state.get())
        assertEquals(listOf("a", "b", "a"), loads)

        // The same commit again reads nothing.
        branches.show(repo, "a")
        runAll()
        assertEquals(listOf("a", "b", "a"), loads)
    }

    @Test
    fun `a result for a commit no longer shown is dropped, but kept`() {
        branches.show(repo, "a")
        background.removeFirst().run()
        branches.show(repo, "b")
        dispatched.removeFirst().run()
        assertEquals(BranchesState.Reading("b"), branches.state.get())
        runAll()
        assertEquals(BranchesState.Read("b", listOf("main", "b")), branches.state.get())

        branches.show(repo, "a")
        assertEquals(BranchesState.Read("a", listOf("main")), branches.state.get())
        assertEquals(listOf("a", "b"), loads)
    }

    @Test
    fun `a newer request stops the git of the one being read, which is neither shown nor kept`() {
        whileLoading = { if (loads.size == 1) branches.show(repo, "b") }
        branches.show(repo, "a")
        runAll()

        assertEquals(listOf(true, false), cancellations.map { it.cancelled })
        assertEquals(BranchesState.Read("b", listOf("main", "b")), branches.state.get())
        whileLoading = {}
        branches.show(repo, "a")
        runAll()
        assertEquals(listOf("a", "b", "a"), loads, "what a stopped git left was not kept")
    }

    @Test
    fun `a request superseded before it starts does not read`() {
        branches.show(repo, "a")
        branches.show(repo, "b")
        branches.show(repo, "c")
        branches.show(repo, "b")
        runAll()
        assertEquals(listOf("b"), loads, "only the commit still shown")
        assertEquals(BranchesState.Read("b", listOf("main", "b")), branches.state.get())
    }

    @Test
    fun `a failure is shown and tried again, nothing shown reads nothing`() {
        branches.show(repo, "c")
        runAll()
        assertEquals(BranchesState.Failed("c"), branches.state.get())
        branches.show(repo, "c")
        runAll()
        assertEquals(listOf("c", "c"), loads)

        branches.show(null, null)
        assertEquals(BranchesState.None, branches.state.get())
        branches.refsChanged()
        runAll()
        assertEquals(listOf("c", "c"), loads)
    }

    @Test
    fun `a result read before the refs changed or another folder opened is not kept`() {
        branches.show(repo, "a")
        background.removeFirst().run()
        branches.clear()
        runAll()
        assertEquals(BranchesState.None, branches.state.get())
        branches.show(repo, "a")
        runAll()
        assertEquals(listOf("a", "a"), loads)
    }

    @Test
    fun `orders the current branch, then local and remote branches by name, without remote HEADs`() {
        assertEquals(
            listOf("main", "alpha", "Beta", "beta", "zeta", "origin/main", "upstream/Dev"),
            orderedBranches(
                listOf(
                    "refs/remotes/upstream/Dev",
                    "refs/heads/zeta",
                    "refs/remotes/origin/HEAD",
                    "refs/heads/beta",
                    "refs/remotes/origin/main",
                    "refs/heads/main",
                    "refs/heads/Beta",
                    "refs/tags/v1",
                    "refs/heads/alpha",
                ),
                "refs/heads/main",
            ),
        )
        assertEquals(listOf("a", "main"), orderedBranches(listOf("refs/heads/main", "refs/heads/a"), null))
    }

    @Test
    fun `the line lists up to six branches, then a link to all of them`() {
        assertEquals(BranchesLine("In no branch", null), branchesLine(emptyList(), all = false))
        assertEquals(BranchesLine("In 1 branch: main", null), branchesLine(listOf("main"), all = false))
        assertEquals(
            BranchesLine("In 3 branches: main, origin/main, feature/x", null),
            branchesLine(listOf("main", "origin/main", "feature/x"), all = false),
        )
        val six = (1..6).map { "b$it" }
        assertEquals(BranchesLine("In 6 branches: b1, b2, b3, b4, b5, b6", null), branchesLine(six, all = false))
        val nine = (1..9).map { "b$it" }
        assertEquals(
            BranchesLine("In 9 branches: b1, b2, b3, b4, b5, b6, …", "Show all (9)"),
            branchesLine(nine, all = false),
        )
        assertEquals(
            BranchesLine("In 9 branches: ${nine.joinToString(", ")}", null),
            branchesLine(nine, all = true),
        )
    }
}
