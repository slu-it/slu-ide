package dev.sluide.gitlog.operations

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitResult
import dev.sluide.gitlog.data.FakeWrites
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommitChangesTest {
    private val root = Path.of("/repo")
    private val files =
        listOf(GitChangedFile(GitChange.RENAMED, "b.txt", "a.txt"), GitChangedFile(GitChange.MODIFIED, "c"))

    @Test
    fun `commits the files and untracked paths and says what it made`() {
        val writes = FakeWrites()
        val operation = CommitChanges("Fix the bug\n\nBody", files, listOf("new.txt"), amend = false)

        val outcome = operation.run(writes, root)

        assertEquals(listOf("commit b.txt,c|new.txt: Fix the bug\\n\\nBody"), writes.calls)
        assertTrue(outcome.result.success)
        assertEquals("Committed 5e6f7a8b: Fix the bug.", outcome.done)
        assertEquals("Committing…", operation.progress)
        assertEquals("Commit Failed", operation.errorTitle)
        assertEquals("The commit could not be made.", operation.failure)
        assertEquals("Commit", operation.fileChange)
        assertTrue(operation.cancellable)
        assertEquals(setOf("b.txt", "c", "new.txt"), operation.paths)
    }

    @Test
    fun `amends, and a subject's own full stop is not doubled`() {
        val writes = FakeWrites()
        val operation = CommitChanges("\n  Reword it.  \n", emptyList(), emptyList(), amend = true)

        val outcome = operation.run(writes, root)

        assertEquals(listOf("commit --amend |: \\n  Reword it.  \\n"), writes.calls)
        assertEquals("Amended 5e6f7a8b: Reword it.", outcome.done)
        assertEquals("Amending…", operation.progress)
        assertEquals("The commit could not be amended.", operation.failure)
    }

    @Test
    fun `a failure has git's message and nothing done`() {
        val writes = FakeWrites()
        writes.result = { GitResult(false, "pre-commit hook failed") }

        val outcome = CommitChanges("m", files, emptyList(), amend = false).run(writes, root)

        assertFalse(outcome.result.success)
        assertEquals("pre-commit hook failed", outcome.result.message)
        assertNull(outcome.done)
    }

    @Test
    fun `cancelled, it says so without a failure and counts as nothing changed`() {
        val writes = FakeWrites()
        writes.result = { GitResult(false, "Cancelled.") }
        val context = RunContext().apply { cancellation.cancel() }

        val outcome = CommitChanges("m", files, emptyList(), amend = false).run(writes, root, context)

        assertTrue(outcome.result.success)
        assertFalse(outcome.result.changed)
        assertEquals("Commit cancelled.", outcome.done)
    }

    @Test
    fun `a commit whose hash cannot be read is still told`() {
        val writes = FakeWrites()
        writes.committed = null

        assertEquals("Committed: m.", CommitChanges("m", files, emptyList(), amend = false).run(writes, root).done)
    }

    @Test
    fun `the subject is the first line that is not blank, cut to fit the header`() {
        assertEquals("Subject", commitSubject("\n\n  Subject \nbody"))
        assertEquals("", commitSubject(""))
        val long = "x".repeat(60)
        assertEquals("x".repeat(49) + "…", commitSubject(long))
        assertEquals("x".repeat(50), commitSubject("x".repeat(50)))
    }
}
