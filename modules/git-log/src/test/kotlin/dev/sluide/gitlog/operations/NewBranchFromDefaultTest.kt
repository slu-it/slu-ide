package dev.sluide.gitlog.operations

import dev.sluide.git.GitFetchChanges
import dev.sluide.git.GitFetchResult
import dev.sluide.git.GitResult
import dev.sluide.gitlog.data.FakeWrites
import dev.sluide.gitlog.data.PromptCancel
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewBranchFromDefaultTest {
    private val root = Path.of("/repo")
    private val defaultRef = "refs/remotes/origin/develop"
    private val operation = PrepareNewBranchFromDefault()
    private var uncancellable = 0
    private val context = RunContext(uncancellable = { uncancellable++ })

    private val fetchedTwo = GitFetchResult(GitResult(true, ""), changes = GitFetchChanges(updatedBranches = 2))
    private val note = assertNotNull(pullFetchNote("origin", fetchedTwo))

    private val writes =
        FakeWrites().also {
            it.currentBranch = "feature"
            it.remoteDefault = defaultRef
            it.fetchResult = { fetchedTwo }
            it.tips = mapOf("main" to "h1", "feature/x" to "h2")
        }

    @Test
    fun `fetches, finds the default branch and reads the branches into the prompt`() {
        val outcome = operation.run(writes, root, context)

        val prompt =
            NewBranchPrompt(defaultRef, "origin/develop", mapOf("main" to "h1", "feature/x" to "h2"), "feature")
        assertEquals(RefOutcome(GitResult(true, ""), note, newBranch = prompt), outcome)
        assertEquals(listOf("fetch origin", "default branch origin", "local branch tips"), writes.calls)
        assertEquals(1, uncancellable)
        assertEquals("Fetching origin…", operation.progress)
        assertEquals("New Branch Failed", operation.errorTitle)
        assertEquals("A branch could not be created from origin's default branch.", operation.failure)
        assertNull(operation.fileChange)
        assertTrue(operation.cancellable)
    }

    @Test
    fun `a detached head gives no current branch`() {
        writes.currentBranch = null

        assertNull(assertNotNull(operation.run(writes, root, context).newBranch).current)
    }

    @Test
    fun `a failed fetch gives git's message and reads nothing more`() {
        writes.fetchResult = { GitFetchResult(GitResult(false, "fatal: unable to access")) }

        val outcome = operation.run(writes, root, context)

        assertEquals(GitResult(false, "fatal: unable to access"), outcome.result)
        assertEquals("origin could not be fetched; no branch was created.", outcome.failure)
        assertNull(outcome.newBranch)
        assertEquals(listOf("fetch origin"), writes.calls)
        assertEquals(0, uncancellable)
    }

    @Test
    fun `a cancelled fetch gives no prompt and reads nothing more`() {
        writes.fetchResult = { GitFetchResult(GitResult(false, "Cancelled."), cancelled = true) }

        val outcome = operation.run(writes, root, context)

        assertEquals(RefOutcome(GitResult(true, ""), "New branch cancelled.", cancelled = true), outcome)
        assertEquals(listOf("fetch origin"), writes.calls)
        assertEquals(0, uncancellable)
    }

    @Test
    fun `without a default branch it fails after the fetch`() {
        writes.remoteDefault = null

        val outcome = operation.run(writes, root, context)

        assertEquals(
            GitResult(false, "origin's default branch could not be found (no origin/HEAD, main or master)."),
            outcome.result,
        )
        assertEquals(note, outcome.done)
        assertEquals(operation.failure, outcome.failure)
        assertNull(outcome.newBranch)
        assertEquals(listOf("fetch origin", "default branch origin"), writes.calls)
        assertEquals(1, uncancellable)
    }

    @Test
    fun `unreadable local branches fail it`() {
        writes.tipsUnreadable = true

        val outcome = operation.run(writes, root, context)

        assertEquals(GitResult(false, "The local branches could not be read."), outcome.result)
        assertEquals(note, outcome.done)
        assertEquals(operation.failure, outcome.failure)
        assertNull(outcome.newBranch)
    }

    @Test
    fun `a fetch that failed after the user cancelled an ssh prompt gives no prompt and no error`() {
        val prompt = PromptCancel()
        writes.fetchResult = {
            prompt.cancel()
            GitFetchResult(GitResult(false, "Permission denied (publickey)."))
        }

        val outcome = operation.run(writes, root, prompt.context())

        assertEquals(RefOutcome(GitResult(true, ""), "New branch cancelled.", cancelled = true), outcome)
        assertTrue(operation.contactsRemote)
    }
}
