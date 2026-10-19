package dev.sluide.gitlog.operations

import dev.sluide.git.GitConflictReason
import dev.sluide.git.GitPendingRebase
import dev.sluide.git.GitRebaseResult
import dev.sluide.git.GitResult
import dev.sluide.git.GitUncheckedConflict
import dev.sluide.gitlog.data.FakeWrites
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RebaseOperationsTest {
    private val root = Path.of("/repo")
    private val onto = "refs/heads/main"
    private val hash = "5e6f7a8b9c0d"

    private val writes = FakeWrites().also { it.ancestry = { _, _ -> false } }

    private val rebase = RebaseBranch("feature", onto, "main")

    private fun pending(conflicts: List<String> = listOf("a.txt")) =
        GitPendingRebase("feature", "1a2b3c4d5e6f", hash, 3, 7, conflicts)

    @Test
    fun `a rebase runs and tells what was done`() {
        val outcome = rebase.run(writes, root)

        assertEquals(RefOutcome(GitResult(true, ""), "Rebased feature onto main."), outcome)
        assertEquals(listOf("rebase merges $onto", "rebase feature $onto"), writes.calls)
        assertEquals("feature", rebase.branch)
        assertEquals("Rebase", rebase.fileChange)
        assertEquals("Rebase Failed", rebase.errorTitle)
    }

    @Test
    fun `nothing is run if the branch contains the target already`() {
        writes.ancestry = { ancestor, descendant ->
            assertEquals(onto, ancestor)
            assertEquals("HEAD", descendant)
            true
        }

        val outcome = rebase.run(writes, root)

        assertEquals("'main' is already contained in 'feature'; nothing to rebase.", outcome.done)
        assertTrue(writes.calls.isEmpty())

        // The backend says the same if it was not up to date when asked.
        writes.ancestry = { _, _ -> false }
        writes.rebaseResult = { GitRebaseResult.UpToDate }
        assertEquals("Nothing to rebase.", rebase.run(writes, root, confirmed = true).done)
    }

    private fun RebaseBranch.run(
        writes: FakeWrites,
        root: Path,
        confirmed: Boolean,
    ) = copy(confirmed = confirmed).run(writes, root)

    @Test
    fun `merge commits are asked about first`() {
        writes.rebaseMerges = 2
        val asked = rebase.run(writes, root)

        val confirmation = assertNotNull(asked.confirmation)
        assertNull(asked.done)
        assertEquals(
            "feature has 2 merge commits since main; the rebase drops them and replays their changes as plain " +
                "commits.",
            confirmation.question,
        )
        assertEquals(REBASE_BUTTON, confirmation.button)
        assertEquals(listOf("rebase merges $onto"), writes.calls)

        writes.rebaseMerges = 1
        assertEquals(
            "feature has 1 merge commit since main; the rebase drops them and replays their changes as plain commits.",
            rebase.run(writes, root).confirmation?.question,
        )

        writes.rebaseMerges = null
        val unknown = rebase.run(writes, root).confirmation
        assertEquals(
            "feature may have merge commits since main; the rebase drops them and replays their changes as plain " +
                "commits.",
            unknown?.question,
        )

        writes.calls.clear()
        val confirmed = confirmation.confirmed.run(writes, root)
        assertEquals("Rebased feature onto main.", confirmed.done)
        assertEquals(listOf("rebase feature $onto"), writes.calls, "not asked again")
    }

    @Test
    fun `a stop is reported with its conflicts`() {
        writes.rebaseResult = { GitRebaseResult.Stopped(pending(), "CONFLICT in a.txt") }

        val outcome = rebase.run(writes, root)

        assertEquals("Rebase of feature stopped at 5e6f7a8b with conflicts.", outcome.done)
        assertEquals(RebaseStop("feature", hash, listOf("a.txt"), "CONFLICT in a.txt"), outcome.rebaseStopped)
        assertTrue(outcome.result.success)

        writes.rebaseResult = { GitRebaseResult.Stopped(pending(emptyList()), "Stopped at 5e6f7a8") }
        assertEquals("Rebase of feature stopped at 5e6f7a8b.", rebase.run(writes, root).done)

        writes.rebaseResult = {
            GitRebaseResult.Stopped(GitPendingRebase("feature", null, null, null, null, emptyList()), "")
        }
        assertEquals("Rebase of feature stopped.", rebase.run(writes, root).done)
    }

    @Test
    fun `a failure is the message of git`() {
        writes.rebaseResult = { GitRebaseResult.Failed("cannot rebase: You have unstaged changes.") }

        val outcome = rebase.run(writes, root)

        assertEquals(GitResult(false, "cannot rebase: You have unstaged changes."), outcome.result)
        assertNull(outcome.done)
        assertNull(outcome.rebaseStopped)
    }

    @Test
    fun `continue tells how the rebase went on`() {
        writes.inProgress = pending()
        assertEquals("Rebased feature.", ContinueRebase().run(writes, root).done)

        writes.rebaseResult = { GitRebaseResult.Stopped(pending(), "CONFLICT") }
        val again = ContinueRebase().run(writes, root)
        assertEquals("Rebase of feature stopped at 5e6f7a8b with conflicts.", again.done)
        assertNotNull(again.rebaseStopped)
        assertEquals(listOf("rebase --continue", "rebase --continue"), writes.calls)
        assertEquals("Continue", ContinueRebase().fileChange)
    }

    @Test
    fun `continue with conflict markers left fails`() {
        writes.rebaseResult = { GitRebaseResult.MarkersLeft(listOf("a.txt", "b.txt")) }

        val outcome = ContinueRebase().run(writes, root)

        assertEquals(GitResult(false, "Conflict markers are left in: a.txt, b.txt"), outcome.result)
        assertNull(outcome.done)
    }

    @Test
    fun `files that cannot be checked are asked about and then continued as on disk`() {
        val files = listOf(GitUncheckedConflict("bin.dat", GitConflictReason.BINARY))
        writes.rebaseResult = { line ->
            if (line.endsWith("as on disk")) GitRebaseResult.Done(hash) else GitRebaseResult.Unchecked(files)
        }

        val asked = ContinueRebase().run(writes, root)

        val confirmation = assertNotNull(asked.confirmation)
        assertEquals("Continue Rebase", confirmation.title)
        assertEquals(COMMIT_AS_ON_DISK_BUTTON, confirmation.button)
        assertEquals("bin.dat — ${reasonText(GitConflictReason.BINARY)}", confirmation.details)
        assertNull(asked.done)

        val done = confirmation.confirmed.run(writes, root)
        assertEquals("Rebase finished.", done.done)
        assertEquals(listOf("rebase --continue", "rebase --continue as on disk"), writes.calls)
        assertEquals(listOf(emptySet(), files.toSet()), writes.confirmedSets)
    }

    @Test
    fun `a detached rebase has no branch in its texts`() {
        val detached = GitPendingRebase(null, "1a2b3c4d5e6f", hash, 1, 2, listOf("a.txt"))
        writes.inProgress = detached
        assertEquals("Rebase finished.", ContinueRebase().run(writes, root).done)

        writes.rebaseResult = { GitRebaseResult.Stopped(detached, "CONFLICT") }
        val stopped = ContinueRebase().run(writes, root)
        assertEquals("Rebase stopped at 5e6f7a8b with conflicts.", stopped.done)
        assertEquals(RebaseStop(null, hash, listOf("a.txt"), "CONFLICT"), stopped.rebaseStopped)

        writes.rebaseResult = { GitRebaseResult.Stopped(detached.copy(stopped = null, conflicts = emptyList()), "") }
        assertEquals("Rebase stopped.", SkipRebase.run(writes, root).done)

        assertEquals(
            "Skip 5e6f7a8b and continue the rebase? Its changes are left out, and what was done to resolve its " +
                "conflicts is lost.",
            skipRebaseQuestion(detached),
        )
        assertEquals(
            "Skip the current commit and continue the rebase of 'feature'? Its changes are left out, and what was " +
                "done to resolve its conflicts is lost.",
            skipRebaseQuestion(pending().copy(stopped = null)),
        )
        assertEquals(
            "Skip 5e6f7a8b and continue the rebase of 'feature'? Its changes are left out, and local changes to " +
                "tracked files are lost.",
            skipRebaseQuestion(pending(emptyList())),
        )
    }

    @Test
    fun `skip drops the commit and goes on`() {
        writes.inProgress = pending()
        assertEquals("Skipped the commit; rebased feature.", SkipRebase.run(writes, root).done)

        writes.rebaseResult = { GitRebaseResult.Stopped(pending(), "CONFLICT") }
        assertNotNull(SkipRebase.run(writes, root).rebaseStopped)
        assertEquals(listOf("rebase --skip", "rebase --skip"), writes.calls)
        assertEquals("Skip", SkipRebase.fileChange)
    }

    @Test
    fun `abort goes back to before the rebase`() {
        writes.inProgress = pending()
        assertEquals("Aborted the rebase of feature.", AbortRebase.run(writes, root).done)
        assertEquals(listOf("rebase --abort"), writes.calls)
        assertEquals("Abort", AbortRebase.fileChange)

        writes.inProgress = null
        assertEquals("Aborted the rebase.", AbortRebase.run(writes, root).done)

        writes.rebaseResult = { GitRebaseResult.Failed("No rebase in progress?") }
        val failed = AbortRebase.run(writes, root)
        assertEquals(GitResult(false, "No rebase in progress?"), failed.result)
        assertNull(failed.done)

        // An abort that leaves the rebase in progress is a failure, not another stop to resolve.
        writes.rebaseResult = { GitRebaseResult.Stopped(pending(), "error: could not remove") }
        val left = AbortRebase.run(writes, root)
        assertEquals(GitResult(false, "error: could not remove"), left.result)
        assertNull(left.done)
        assertNull(left.rebaseStopped)
    }

    @Test
    fun `all hold the status and none can be cancelled`() {
        listOf(rebase, ContinueRebase(), SkipRebase, AbortRebase).forEach {
            assertTrue(it.holdsStatus)
            assertTrue(!it.cancellable)
        }
    }

    @Test
    fun `the bar tells the rebase in progress`() {
        val onto = "1a2b3c4d5e6f"
        assertEquals(
            "Rebasing feature onto 1a2b3c4d: stopped at 5e6f7a8b (3 of 7); 2 files have conflicts.",
            pendingRebaseText(GitPendingRebase("feature", onto, hash, 3, 7, listOf("a", "b"))),
        )
        assertEquals(
            "Rebasing feature onto 1a2b3c4d: stopped at 5e6f7a8b (3 of 7); 1 file has conflicts.",
            pendingRebaseText(GitPendingRebase("feature", onto, hash, 3, 7, listOf("a"))),
        )
        assertEquals(
            "Rebasing feature onto 1a2b3c4d: stopped at 5e6f7a8b.",
            pendingRebaseText(GitPendingRebase("feature", onto, hash, null, null, emptyList())),
        )
        assertEquals(
            "Rebasing feature onto 1a2b3c4d (3 of 7).",
            pendingRebaseText(GitPendingRebase("feature", onto, null, 3, 7, emptyList())),
        )
        assertEquals(
            "Rebasing onto 1a2b3c4d: stopped at 5e6f7a8b (3 of 7).",
            pendingRebaseText(GitPendingRebase(null, onto, hash, 3, 7, emptyList())),
        )
        assertEquals("Rebasing.", pendingRebaseText(GitPendingRebase(null, null, null, null, null, emptyList())))
    }

    @Test
    fun `aborting from the bar asks`() {
        assertEquals(
            "Abort the rebase of 'feature'? The branch goes back to where it was; the commits rebased so far and " +
                "what was done to resolve conflicts are lost.",
            abortRebaseQuestion("feature"),
        )
        assertEquals(
            "Abort the rebase? HEAD goes back to where it was; the commits rebased so far and what was done to " +
                "resolve conflicts are lost.",
            abortRebaseQuestion(null),
        )
    }
}
