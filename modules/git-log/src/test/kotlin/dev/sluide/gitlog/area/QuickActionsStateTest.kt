package dev.sluide.gitlog.area

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitStash
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The header's rebase and new branch state (ADRs 0063, 0181) read while the log is collapsed, with reads run when the
 * test says so.
 */
class QuickActionsStateTest {
    private val repo = Path.of("/repo")
    private val other = Path.of("/other")

    private var remotes: List<String>? = listOf("upstream", "origin")
    private var branch: String? = "refs/heads/main"
    private var head: String? = "h1"
    private var stashList: List<GitStash>? = null
    private var expanded = false
    private val reads = mutableListOf<Runnable>()
    private val readRoots = mutableListOf<Path>()

    private val state =
        QuickActionsState(
            { root ->
                readRoots.add(root)
                remotes
            },
            { branch },
            { head },
            { stashList },
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
        assertFalse(state.rebasePossible.get(), "not before the read ran")

        runReads()

        assertTrue(state.rebasePossible.get())
        assertEquals(listOf(repo), readRoots)
    }

    @Test
    fun `no origin, a detached HEAD or no commit turn it off`() {
        remotes = listOf("upstream")
        state.open(repo)
        runReads()
        assertFalse(state.rebasePossible.get(), "no origin")

        remotes = null
        state.refresh()
        runReads()
        assertFalse(state.rebasePossible.get(), "remotes unreadable")

        remotes = listOf("origin")
        branch = null
        state.refresh()
        runReads()
        assertFalse(state.rebasePossible.get(), "detached")

        branch = "refs/heads/main"
        head = null
        state.refresh()
        runReads()
        assertFalse(state.rebasePossible.get(), "no commit")

        head = "h1"
        state.refresh()
        runReads()
        assertTrue(state.rebasePossible.get(), "a refresh reads again")
    }

    @Test
    fun `the new branch needs only origin, also on a detached HEAD or without a commit`() {
        remotes = listOf("upstream")
        state.open(repo)
        runReads()
        assertFalse(state.newBranchPossible.get(), "no origin")

        remotes = listOf("origin")
        branch = null
        head = null
        state.refresh()
        runReads()
        assertTrue(state.newBranchPossible.get(), "detached and without a commit")
        assertFalse(state.rebasePossible.get())

        remotes = null
        state.refresh()
        runReads()
        assertFalse(state.newBranchPossible.get(), "remotes unreadable")

        // The expanded log's read: no HEAD ref, nothing checked out.
        state.refsRead(listOf("origin"), GitRefs(emptyList(), null), null)
        assertTrue(state.newBranchPossible.get(), "from the pages' read")
        assertFalse(state.rebasePossible.get())
        state.refsRead(listOf("upstream"), null, null)
        assertFalse(state.newBranchPossible.get(), "pages without origin")
    }

    @Test
    fun `no repository turns it off and reads nothing`() {
        state.open(repo)
        runReads()
        assertTrue(state.rebasePossible.get())
        assertTrue(state.newBranchPossible.get())

        state.open(null)
        state.refresh()

        assertFalse(state.rebasePossible.get())
        assertFalse(state.newBranchPossible.get())
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
        assertTrue(state.rebasePossible.get())
        assertTrue(state.newBranchPossible.get())
        remotes = listOf("upstream")
        firstRead.run()
        assertTrue(state.rebasePossible.get(), "the stale read, which finds no origin, was ignored")
        assertTrue(state.newBranchPossible.get(), "also for the new branch")

        // The same for an older refresh finishing after a newer one.
        reads.clear()
        remotes = listOf("upstream")
        state.refresh()
        val older = reads.last()
        remotes = listOf("origin")
        state.refresh()
        reads.last().run()
        assertTrue(state.rebasePossible.get())
        remotes = listOf("upstream")
        older.run()
        assertTrue(state.rebasePossible.get())
        assertTrue(state.newBranchPossible.get())
    }

    @Test
    fun `nothing is read while expanded, collapsing keeps the state and a refresh then reads`() {
        state.open(repo)
        runReads()
        assertTrue(state.rebasePossible.get())

        expanded = true
        remotes = listOf("upstream")
        state.refresh()
        state.open(other)
        assertTrue(reads.isEmpty(), "expanded: the pages read it")
        assertFalse(state.rebasePossible.get(), "a newly opened root is not known until the pages read")
        assertFalse(state.newBranchPossible.get(), "nor for the new branch")

        state.refsRead(
            listOf("origin"),
            GitRefs(listOf(GitRef("HEAD", GitRefKind.HEAD, "h1")), "refs/heads/main"),
            stashes(2),
        )
        expanded = false
        assertEquals(2, state.stashCount.get(), "the pages' stashes, kept when collapsing")
        assertTrue(state.rebasePossible.get(), "collapsing keeps what the pages read")
        assertTrue(state.newBranchPossible.get())

        state.refresh()
        runReads()
        assertFalse(state.rebasePossible.get(), "collapsed, a refresh reads again")
        assertFalse(state.newBranchPossible.get())
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

    private fun stashes(count: Int) = List(count) { GitStash(it, "c$it", 0, "On main: $it") }

    @Test
    fun `the stash count is 0 until read, then follows the read, 0 when it fails`() {
        stashList = stashes(2)
        state.open(repo)
        assertEquals(0, state.stashCount.get(), "not before the read ran")

        runReads()
        assertEquals(2, state.stashCount.get())

        stashList = null
        state.refresh()
        runReads()
        assertEquals(0, state.stashCount.get(), "unreadable")
    }

    @Test
    fun `opening a root resets the stash count, also without a repository`() {
        stashList = stashes(3)
        state.open(repo)
        runReads()
        assertEquals(3, state.stashCount.get())

        expanded = true
        state.open(other)
        assertEquals(0, state.stashCount.get(), "a new root is not known until the pages read")

        expanded = false
        state.refresh()
        runReads()
        assertEquals(3, state.stashCount.get())
        state.open(null)
        assertEquals(0, state.stashCount.get())
    }

    @Test
    fun `an overtaken read does not set the stash count`() {
        stashList = stashes(1)
        state.open(repo)
        val older = reads.last()
        // Only the generation changes: refsRead sets 4, and the older read, which sees other stashes, must not undo it.
        state.refsRead(listOf("origin"), null, stashes(4))
        stashList = stashes(2)
        older.run()

        assertEquals(4, state.stashCount.get())
    }

    @Test
    fun `the pages' read sets the stash count, null meaning none`() {
        state.refsRead(listOf("origin"), null, stashes(3))
        assertEquals(3, state.stashCount.get())

        state.refsRead(listOf("origin"), null, null)
        assertEquals(0, state.stashCount.get())
    }

    @Test
    fun `what the expanded log read wins over an older read`() {
        state.open(repo)
        val refs = GitRefs(listOf(GitRef("HEAD", GitRefKind.HEAD, "h1")), "refs/heads/main")
        state.refsRead(listOf("origin"), refs, null)
        assertTrue(state.rebasePossible.get())

        remotes = listOf("upstream")
        runReads()

        assertTrue(state.rebasePossible.get(), "the read started before the pages' was overtaken")
        assertTrue(state.newBranchPossible.get())
    }
}
