package dev.sluide.gitlog.actions

import dev.sluide.git.GitResult
import dev.sluide.gitlog.operations.RefOutcome
import dev.sluide.gitlog.operations.RenameBranch
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorHeaderTest {
    private val operation = RenameBranch("old", "new")

    @Test
    fun `a failure that changed something says what was done`() {
        val outcome = RefOutcome(GitResult(false, "hook failed", changed = true), "Checked out main.")
        assertEquals("Checked out main.", errorHeader(operation, outcome))
    }

    @Test
    fun `what was done is not the header while nothing changed`() {
        val outcome = RefOutcome(GitResult(false, "broken", changed = false), "Renamed.", failure = "Not renamed.")
        assertEquals("Not renamed.", errorHeader(operation, outcome))
    }

    @Test
    fun `the question of the outcome comes before the operation's`() {
        val outcome = RefOutcome(GitResult(false, "broken"), null, failure = "origin could not be fetched.")
        assertEquals("origin could not be fetched.", errorHeader(operation, outcome))
    }

    @Test
    fun `else the operation's question is the header`() {
        assertEquals(
            "Branch old could not be renamed.",
            errorHeader(operation, RefOutcome(GitResult(false, "broken"), null)),
        )
        assertEquals(
            "Branch old could not be renamed.",
            errorHeader(operation, RefOutcome(GitResult(false, "broken", changed = false), "Done.")),
        )
    }
}
