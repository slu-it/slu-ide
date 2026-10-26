package dev.sluide.gitlog.operations

import dev.sluide.git.GitResult
import dev.sluide.git.GitRewordCheck
import dev.sluide.git.GitRewordResult
import dev.sluide.gitlog.data.FakeWrites
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RewordOperationsTest {
    private val root = Path.of("/repo")
    private val hash = "1a2b3c4d5e6f7a8b9c0d"
    private val writes = FakeWrites()

    @Test
    fun `a refused check shows its reason and asks nothing`() {
        writes.rewordCheck = GitRewordCheck.Refused("It is on main.")
        val check = CheckReword("feature", hash)

        val outcome = check.run(writes, root)

        assertEquals("It is on main.", outcome.done)
        assertNull(outcome.reword)
        assertEquals(listOf("reword check feature $hash"), writes.calls)
        assertEquals("Reading the message of 1a2b3c4d…", check.progress)
        assertNull(check.fileChange)
        assertFalse(check.holdsStatus)
    }

    @Test
    fun `an allowed check carries what the dialog shows`() {
        writes.rewordCheck = GitRewordCheck.Allowed("Subject", listOf("fix-x"), "origin/feature")

        val outcome = CheckReword("feature", hash).run(writes, root)

        assertNull(outcome.done)
        assertTrue(outcome.result.success)
        assertEquals(RewordPrompt("feature", hash, "Subject", listOf("fix-x"), "origin/feature"), outcome.reword)
    }

    @Test
    fun `changing the message tells what happened`() {
        val reword = RewordCommit("feature", hash, "New")

        assertEquals(RefOutcome(GitResult(true, ""), "Changed the message of 1a2b3c4d."), reword.run(writes, root))
        assertEquals(listOf("reword feature $hash New"), writes.calls)
        assertEquals("Changing the message of 1a2b3c4d…", reword.progress)
        assertNull(reword.fileChange)
        assertFalse(reword.holdsStatus)

        writes.rewordResult = GitRewordResult.Unchanged
        assertEquals("The message of 1a2b3c4d is unchanged.", reword.run(writes, root).done)

        writes.rewordResult = GitRewordResult.Failed("moved meanwhile")
        val failed = reword.run(writes, root)
        assertEquals(GitResult(false, "moved meanwhile"), failed.result)
        assertNull(failed.done)
        assertEquals("Edit Commit Message Failed", reword.errorTitle)
        assertEquals("The message of 1a2b3c4d could not be changed.", reword.failure)
    }

    @ParameterizedTest
    @CsvSource(
        "'Old', '', false",
        "'Old', '  \\n ', false",
        "'Old', 'Old', false",
        "'Old\\n\\nBody', '\\n\\nOld  \\n\\nBody \\n\\n', false",
        "'Old', 'New', true",
        "'Old', 'Old\\nmore', true",
        "'Old', ' Old', true",
    )
    fun `a message can be changed if it is not blank and differs`(
        original: String,
        edited: String,
        expected: Boolean,
    ) {
        assertEquals(expected, canChangeMessage(original.unescape(), edited.unescape()))
    }

    private fun String.unescape() = replace("\\n", "\n")

    @Test
    fun `the notes name the branches that keep the old commit and the push`() {
        assertEquals(emptyList(), rewordNotes(emptyList(), null))
        assertEquals(listOf("fix-x keeps the old commit."), rewordNotes(listOf("fix-x"), null))
        assertEquals(listOf("fix-x and fix-y keep the old commit."), rewordNotes(listOf("fix-x", "fix-y"), null))
        assertEquals(
            listOf("a, b and c keep the old commit."),
            rewordNotes(listOf("a", "b", "c"), null),
        )
        assertEquals(
            listOf("a, b, c and 2 more keep the old commit."),
            rewordNotes(listOf("a", "b", "c", "d", "e"), null),
        )
        assertEquals(
            listOf("a, b, c and 1 more keep the old commit."),
            rewordNotes(listOf("a", "b", "c", "d"), null),
        )
        assertEquals(
            listOf("Already pushed to origin/feature; a force push will be needed afterwards."),
            rewordNotes(emptyList(), "origin/feature"),
        )
        assertEquals(2, rewordNotes(listOf("a"), "origin/a").size)
    }
}
