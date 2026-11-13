package dev.sluide.gitlog.area

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The header's rebase state read while the log is collapsed (ADR 0063), with reads run when the test says so. */
class QuickRebaseStateTest {
    private val repo = Path.of("/repo")
    private val other = Path.of("/other")

    private var remotes: List<String>? = listOf("upstream", "origin")
    private var branch: String? = "refs/heads/main"
    private var head: String? = "h1"
    private var expanded = false
    private val reads = mutableListOf<Runnable>()
    private val readRoots = mutableListOf<Path>()

    private val state =
        QuickRebaseState(
            { root ->
                readRoots.add(root)
                remotes
            },
            { branch },
            { head },
            { expanded },
            reads::add,
            Runnable::run,
        )

    private fun runReads() {
        reads.toList().also { reads.clear() }.forEach(Runnable::run)
    }

    @Test
    fun `it is off until read, then on with origin, a branch and a commit`() {
        state.open(repo)
        assertFalse(state.possible.get(), "not before the read ran")

        runReads()

        assertTrue(state.possible.get())
        assertEquals(listOf(repo), readRoots)
    }

    @Test
    fun `no origin, a detached HEAD or no commit turn it off`() {
        remotes = listOf("upstream")
        state.open(repo)
        runReads()
        assertFalse(state.possible.get(), "no origin")

        remotes = null
        state.refresh()
        runReads()
        assertFalse(state.possible.get(), "remotes unreadable")

        remotes = listOf("origin")
        branch = null
        state.refresh()
        runReads()
        assertFalse(state.possible.get(), "detached")

        branch = "refs/heads/main"
        head = null
        state.refresh()
        runReads()
        assertFalse(state.possible.get(), "no commit")

        head = "h1"
        state.refresh()
        runReads()
        assertTrue(state.possible.get(), "a refresh reads again")
    }

    @Test
    fun `no repository turns it off and reads nothing`() {
        state.open(repo)
        runReads()
        assertTrue(state.possible.get())

        state.open(null)
        state.refresh()

        assertFalse(state.possible.get())
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `a read overtaken by another root or a newer refresh is dropped`() {
        // The first root's read ends after the second root was opened: it must not show.
        remotes = listOf("upstream")
        state.open(repo)
        val firstRead = reads.last()
        remotes = listOf("origin")
        state.open(other)
        reads.last().run()
        assertTrue(state.possible.get())
        remotes = listOf("upstream")
        firstRead.run()
        assertTrue(state.possible.get(), "the stale read, which finds no origin, was ignored")

        // The same for an older refresh finishing after a newer one.
        reads.clear()
        remotes = listOf("upstream")
        state.refresh()
        val older = reads.last()
        remotes = listOf("origin")
        state.refresh()
        reads.last().run()
        assertTrue(state.possible.get())
        remotes = listOf("upstream")
        older.run()
        assertTrue(state.possible.get())
    }

    @Test
    fun `nothing is read while expanded, collapsing keeps the state and a refresh then reads`() {
        state.open(repo)
        runReads()
        assertTrue(state.possible.get())

        expanded = true
        remotes = listOf("upstream")
        state.refresh()
        state.open(other)
        assertTrue(reads.isEmpty(), "expanded: the pages read it")
        assertFalse(state.possible.get(), "a newly opened root is not known until the pages read")

        state.refsRead(listOf("origin"), GitRefs(listOf(GitRef("HEAD", GitRefKind.HEAD, "h1")), "refs/heads/main"))
        expanded = false
        assertTrue(state.possible.get(), "collapsing keeps what the pages read")

        state.refresh()
        runReads()
        assertFalse(state.possible.get(), "collapsed, a refresh reads again")
    }

    @Test
    fun `a read that was overtaken or closed does not run git`() {
        state.open(repo)
        state.refresh()
        runReads()
        assertEquals(listOf(repo), readRoots, "the first read skipped git")

        state.refresh()
        state.close()
        runReads()
        assertEquals(listOf(repo), readRoots, "a closed state reads nothing")
        state.refresh()
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `what the expanded log read wins over an older read`() {
        state.open(repo)
        val refs = GitRefs(listOf(GitRef("HEAD", GitRefKind.HEAD, "h1")), "refs/heads/main")
        state.refsRead(listOf("origin"), refs)
        assertTrue(state.possible.get())

        remotes = listOf("upstream")
        runReads()

        assertTrue(state.possible.get(), "the read started before the pages' was overtaken")
    }
}
