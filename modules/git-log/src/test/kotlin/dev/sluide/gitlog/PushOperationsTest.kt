package dev.sluide.gitlog

import dev.sluide.git.GitCancellation
import dev.sluide.git.GitPushRejection
import dev.sluide.git.GitPushResult
import dev.sluide.git.GitResult
import dev.sluide.gitlog.data.FakeWrites
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The push operation and its summary (ADR 0049), without git. */
class PushOperationsTest {
    private val repo = Path.of("/repo")

    private fun push(
        force: Boolean = false,
        target: String = "main",
        count: Int = 3,
        more: Boolean = false,
    ) = PushOperation("origin", "main", target, force, null, setUpstream = false, count = count, more = more)

    @Test
    fun `the progress, error title and failure name the branch and where it goes`() {
        assertEquals("Pushing main to origin/main…", push().progress)
        assertEquals("Force pushing main to origin/main…", push(force = true).progress)
        assertEquals("Push Failed", push().errorTitle)
        assertEquals("main could not be pushed to origin/feature.", push(target = "feature").failure)
        assertNull(push().fileChange, "no unsaved-editor question")
        assertTrue(push().cancellable)
    }

    @Test
    fun `the summary tells what was pushed`() {
        val pushed = GitPushResult(GitResult(true, ""))
        assertEquals("Pushed 3 commits to origin/main.", pushSummary(push(), pushed))
        assertEquals("Pushed 1 commit to origin/main.", pushSummary(push(count = 1), pushed))
        assertEquals("Pushed 1000+ commits to origin/main.", pushSummary(push(count = 1000, more = true), pushed))
        assertEquals("Pushed main to origin/main.", pushSummary(push(count = 0), pushed))
        assertEquals("Force pushed main to origin/main.", pushSummary(push(force = true), pushed))
        assertEquals(
            "Pushed main to origin/feature (new branch).",
            pushSummary(push(target = "feature"), pushed.copy(created = true)),
        )
        assertEquals("Everything up to date.", pushSummary(push(), pushed.copy(upToDate = true)))
    }

    @Test
    fun `run passes everything to git and turns its result into the outcome`() {
        val writes = FakeWrites()
        val operation =
            PushOperation("origin", "main", "feature", true, "a".repeat(40), setUpstream = true, count = 2)

        val outcome = operation.run(writes, repo)

        assertEquals(listOf("push -u --force-with-lease=feature:${"a".repeat(40)} origin main:feature"), writes.calls)
        assertEquals("Force pushed main to origin/feature.", outcome.done)
        assertTrue(outcome.result.success)

        writes.pushResult = {
            GitPushResult(GitResult(false, "rejected first\nhint"), rejection = GitPushRejection.NON_FAST_FORWARD)
        }
        val failed = push().run(writes, repo)
        assertFalse(failed.result.success)
        assertEquals("rejected first\nhint", failed.result.message)
        assertNull(failed.done)
    }

    @Test
    fun `a cancelled push only says so`() {
        val writes = FakeWrites()
        val cancellation = GitCancellation().apply { cancel() }

        val outcome = push().run(writes, repo, RunContext(cancellation))

        assertEquals("Push cancelled; it may have reached origin. Fetch to check.", outcome.done)
        assertTrue(outcome.result.success, "no dialog")
        assertFalse(outcome.result.changed)
    }

    @Test
    fun `main and master are protected from force pushes, in any case`() {
        assertTrue(isProtectedBranch("main"))
        assertTrue(isProtectedBranch("master"))
        assertTrue(isProtectedBranch("Main"), "some remotes ignore the case of refs")
        assertTrue(isProtectedBranch("MASTER"))
        assertFalse(isProtectedBranch("feature"))
        assertFalse(isProtectedBranch("main2"))
        assertFalse(isProtectedBranch("team/main"))
    }
}
