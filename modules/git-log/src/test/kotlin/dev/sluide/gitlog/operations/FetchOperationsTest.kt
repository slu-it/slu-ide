package dev.sluide.gitlog.operations

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitFetchChanges
import dev.sluide.git.GitFetchResult
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitResult
import dev.sluide.gitlog.data.BranchNode
import dev.sluide.gitlog.data.BranchParent
import dev.sluide.gitlog.data.FakeWrites
import dev.sluide.gitlog.data.branchNodes
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FetchOperationsTest {
    private val root = Path.of("/repo")

    private fun ok(changes: GitFetchChanges? = GitFetchChanges()) =
        GitFetchResult(GitResult(true, ""), changes = changes)

    private fun failed(
        message: String,
        changes: GitFetchChanges? = GitFetchChanges(),
    ) = GitFetchResult(GitResult(false, message), changes = changes)

    private val cancelledResult = GitFetchResult(GitResult(false, "Cancelled."), cancelled = true)

    @Test
    fun `fetches one remote and says what changed`() {
        val writes = FakeWrites()
        writes.fetchResult =
            { ok(GitFetchChanges(newBranches = 1, updatedBranches = 2, prunedBranches = 1, newTags = 3)) }

        val outcome = Fetch("upstream").run(writes, root)

        assertEquals(listOf("fetch upstream"), writes.calls)
        assertTrue(outcome.result.success)
        assertEquals("Fetched upstream: 1 new branch, 2 branches updated, 1 branch pruned, 3 new tags.", outcome.done)
        assertEquals("Fetching upstream…", Fetch("upstream").progress)
        assertEquals("upstream could not be fetched.", Fetch("upstream").failure)
        assertEquals("Fetch Failed", Fetch().errorTitle)
        assertNull(Fetch().fileChange, "no unsaved-editor question")
        assertTrue(Fetch().cancellable)
    }

    @Test
    fun `fetches all remotes one after the other, telling which, and sums up what changed`() {
        val writes = FakeWrites()
        writes.remotes = listOf("origin", "fork")
        writes.fetchResult = { remote ->
            if (remote ==
                "origin"
            ) {
                ok(GitFetchChanges(updatedBranches = 2))
            } else {
                ok(GitFetchChanges(updatedBranches = 1, prunedBranches = 1))
            }
        }
        val progress = mutableListOf<String>()

        val outcome = Fetch().run(writes, root, RunContext(progress = progress::add))

        assertEquals(listOf("fetch origin", "fetch fork"), writes.calls)
        assertEquals(listOf("Fetching origin…", "Fetching fork…"), progress)
        assertEquals("Fetched 2 remotes: 3 branches updated, 1 branch pruned.", outcome.done)

        writes.fetchResult = { ok() }
        assertEquals("Fetched 2 remotes: nothing new.", Fetch().run(writes, root).done)
        writes.remotes = listOf("origin")
        assertEquals("Fetched origin: nothing new.", Fetch().run(writes, root).done)
        writes.fetchResult = { ok(changes = null) }
        assertEquals("Fetched origin.", Fetch().run(writes, root).done, "changes not readable")
    }

    @Test
    fun `a failing remote does not stop the others and is listed with git's message`() {
        val writes = FakeWrites()
        writes.remotes = listOf("origin", "gone", "fork")
        writes.fetchResult = { remote ->
            if (remote ==
                "gone"
            ) {
                failed("fatal: 'gone' does not appear to be a git repository")
            } else {
                ok(GitFetchChanges(newTags = 1))
            }
        }

        val outcome = Fetch().run(writes, root)

        assertEquals(listOf("fetch origin", "fetch gone", "fetch fork"), writes.calls)
        assertFalse(outcome.result.success)
        assertEquals("fatal: 'gone' does not appear to be a git repository", outcome.result.message)
        assertEquals("gone could not be fetched.", outcome.failure)
        assertEquals("Fetched 2 of 3 remotes: 2 new tags.", outcome.done)

        writes.fetchResult =
            { remote -> if (remote == "origin") ok(GitFetchChanges(newTags = 1)) else failed("no $remote") }
        val two = Fetch().run(writes, root)
        assertEquals("gone:\nno gone\n\nfork:\nno fork", two.result.message)
        assertEquals("2 remotes could not be fetched: gone, fork.", two.failure)
        assertEquals("Fetched origin: 1 new tag.", two.done)

        writes.fetchResult = { remote -> failed("no $remote") }
        assertNull(Fetch().run(writes, root).done, "nothing fetched, nothing changed")
    }

    @Test
    fun `a remote that failed but changed refs is told with its changes`() {
        val writes = FakeWrites()
        writes.fetchResult = {
            failed(" ! [rejected] v1 -> v1  (would clobber existing tag)", GitFetchChanges(updatedBranches = 1))
        }

        val outcome = Fetch().run(writes, root)

        assertFalse(outcome.result.success)
        assertEquals("Fetched origin with errors: 1 branch updated.", outcome.done)
        assertEquals("origin could not be fetched.", outcome.failure)
    }

    @Test
    fun `cancelling skips the remaining remotes and tells what was fetched before`() {
        val writes = FakeWrites()
        writes.remotes = listOf("origin", "slow", "fork")
        val cancellation = GitCancellation()
        writes.fetchResult = { remote ->
            if (remote == "slow") {
                cancellation.cancel()
                cancelledResult
            } else {
                ok(GitFetchChanges(newBranches = 1))
            }
        }

        val outcome = Fetch().run(writes, root, RunContext(cancellation))

        assertEquals(listOf("fetch origin", "fetch slow"), writes.calls)
        assertTrue(outcome.result.success, "no error dialog")
        assertEquals("Fetch cancelled. Fetched so far: 1 new branch.", outcome.done)

        writes.calls.clear()
        val before = Fetch().run(writes, root, RunContext(GitCancellation().apply { cancel() }))
        assertEquals(emptyList(), writes.calls)
        assertEquals("Fetch cancelled.", before.done)
    }

    @Test
    fun `a cancel after the last remote was fetched changes nothing`() {
        val writes = FakeWrites()
        val cancellation = GitCancellation()
        writes.fetchResult = {
            cancellation.cancel()
            ok(GitFetchChanges(newBranches = 1))
        }

        assertEquals("Fetched origin: 1 new branch.", Fetch().run(writes, root, RunContext(cancellation)).done)
    }

    @Test
    fun `without remotes nothing is fetched and the header says so`() {
        val writes = FakeWrites()
        writes.remotes = emptyList()

        val outcome = Fetch().run(writes, root)

        assertEquals(emptyList(), writes.calls)
        assertTrue(outcome.result.success)
        assertEquals("No remotes to fetch.", outcome.done)

        writes.remotes = null
        val unreadable = Fetch().run(writes, root)
        assertFalse(unreadable.result.success)
        assertEquals("The remotes of the repository could not be read.", unreadable.result.message)
        assertEquals("Nothing was fetched.", unreadable.failure)
    }

    @Test
    fun `the summary counts every kind of change`() {
        val all = GitFetchChanges(2, 1, 3, 1, 2, 1)
        assertEquals(
            "Fetched origin: 2 new branches, 1 branch updated, 3 branches pruned, 1 new tag, 2 tags updated, " +
                "1 tag pruned.",
            fetchSummary(mapOf("origin" to ok(all)), cancelled = false),
        )
        assertEquals(
            "Fetched 2 remotes: 1 new branch.",
            fetchSummary(mapOf("a" to ok(null), "b" to ok(GitFetchChanges(newBranches = 1))), cancelled = false),
            "a remote whose changes are unknown adds none",
        )
    }

    @Test
    fun `the summary names the remotes that were not pruned`() {
        val mirror = ok().copy(pruned = false)
        assertEquals(
            "Fetched 2 remotes: nothing new. Not pruned: backup, as its fetch refspecs write outside refs/remotes/.",
            fetchSummary(mapOf("origin" to ok(), "backup" to mirror), cancelled = false),
        )
        assertEquals(
            "Fetch cancelled. Not pruned: a, b, as their fetch refspecs write outside refs/remotes/.",
            fetchSummary(mapOf("a" to mirror, "b" to mirror.copy(cancelled = true)), cancelled = true),
        )
        assertNull(fetchSummary(mapOf("a" to mirror.copy(result = GitResult(false, "no"))), cancelled = false))
    }

    @Test
    fun `the branches pane fetches the remote a selected node is or is in, among the remotes git has`() {
        val refs =
            GitRefs(
                listOf(
                    GitRef("refs/heads/main", GitRefKind.LOCAL_BRANCH, "h1"),
                    GitRef("refs/remotes/origin/fix/a", GitRefKind.REMOTE_BRANCH, "h1"),
                    GitRef("refs/remotes/team/a/main", GitRefKind.REMOTE_BRANCH, "h1"),
                    GitRef("refs/remotes/team/a/fix/b", GitRefKind.REMOTE_BRANCH, "h1"),
                    GitRef("refs/remotes/old/main", GitRefKind.REMOTE_BRANCH, "h1"),
                    GitRef("refs/remotes/trunk", GitRefKind.REMOTE_BRANCH, "h1"),
                    GitRef("refs/tags/v1", GitRefKind.TAG, "h1"),
                ),
                "refs/heads/main",
            )
        val nodes = branchNodes(refs)
        val remotes = listOf("origin", "team/a", "trunk")

        fun node(path: String): BranchNode {
            fun find(nodes: List<BranchNode>): BranchNode? =
                nodes.firstNotNullOfOrNull { node ->
                    if (node.path == path) node else (node as? BranchParent)?.children?.let(::find)
                }
            return checkNotNull(find(nodes)) { path }
        }

        fun fetch(vararg paths: String) = branchActions(paths.map(::node), remotes = remotes).fetch

        assertEquals("origin", fetch("Remote/origin"))
        assertEquals("origin", fetch("Remote/origin/fix"), "a folder of a remote")
        assertEquals("origin", fetch("Remote/origin/fix/a"))
        assertNull(fetch("Remote/team"), "not a remote: only team/a is")
        assertEquals("team/a", fetch("Remote/team/a"), "the folder that is the remote")
        assertEquals("team/a", fetch("Remote/team/a/main"))
        assertEquals("team/a", fetch("Remote/team/a/fix/b"))
        assertNull(fetch("Remote/old"), "a removed remote")
        assertNull(fetch("Remote/old/main"))
        assertNull(fetch("Remote/trunk"), "a remote branch named like a remote is not in it")
        assertNull(fetch("Remote"))
        assertNull(fetch("Local/main"))
        assertNull(fetch("Tags/v1"))
        assertNull(fetch("Remote/origin", "Remote/old"))
        assertNull(branchActions(listOf(node("Remote/origin"))).fetch, "remotes not known")
        assertEquals(
            "team/a",
            branchActions(listOf(node("Remote/team/a/main")), remotes = listOf("team", "team/a")).fetch,
            "the longest match",
        )
    }

    @Test
    fun `the writer cancels only a cancellable run and passes its progress on`() {
        val background = mutableListOf<Runnable>()
        val writes = FakeWrites()
        writes.remotes = listOf("origin", "fork")
        val writer = RefWriter(writes, background::add, Runnable::run, BackgroundTasks.forTests())
        val outcomes = mutableListOf<RefOutcome>()
        val progress = mutableListOf<String>()

        assertTrue(writer.run(root, CheckoutBranch("main"), outcomes::add))
        assertFalse(writer.cancellable.get())
        assertFalse(writer.cancel(), "a checkout is never stopped")
        background.removeFirst().run()
        assertEquals("Checked out main.", outcomes.single().done)

        assertTrue(writer.run(root, Fetch(), progress::add, outcomes::add))
        assertTrue(writer.cancellable.get())
        assertTrue(writer.cancel())
        assertFalse(writer.cancellable.get(), "cancelled once")
        assertTrue(writer.running.get(), "until git has stopped")
        background.removeFirst().run()

        assertEquals("Fetch cancelled.", outcomes.last().done)
        assertEquals(emptyList(), progress, "no remote fetched")
        assertFalse(writer.running.get())
        assertFalse(writer.cancel())

        writer.run(root, Fetch(), progress::add, outcomes::add)
        background.removeFirst().run()
        assertEquals(listOf("Fetching origin…", "Fetching fork…"), progress)

        progress.clear()
        writer.run(root, Fetch(), progress::add, outcomes::add)
        writer.forget()
        background.removeFirst().run()
        assertEquals(emptyList(), progress, "dropped with the outcome")
    }
}
