package dev.sluide.databases.result

import dev.sluide.databases.connect.CommitMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubmitPlanTest {
    @Test
    fun `deletes are asked about only when they are committed at once`() {
        assertTrue(SubmitPlan.asks(CommitMode.AUTO, pendingTransaction = false, deletes = 1))
        assertFalse(SubmitPlan.asks(CommitMode.AUTO, pendingTransaction = false, deletes = 0))
        assertFalse(SubmitPlan.asks(CommitMode.AUTO, pendingTransaction = true, deletes = 1))
        assertFalse(SubmitPlan.asks(CommitMode.MANUAL, pendingTransaction = false, deletes = 1))
    }

    @Test
    fun `idle Auto mode commits at once, a transaction of the user or Manual mode does not`() {
        assertEquals(SubmitPlan.Run(true), SubmitPlan.decide(CommitMode.AUTO, false, deletes = 1, asked = true))
        assertEquals(SubmitPlan.Run(true), SubmitPlan.decide(CommitMode.AUTO, false, deletes = 0, asked = false))
        assertEquals(SubmitPlan.Run(false), SubmitPlan.decide(CommitMode.AUTO, true, deletes = 1, asked = false))
        assertEquals(SubmitPlan.Run(false), SubmitPlan.decide(CommitMode.MANUAL, false, deletes = 1, asked = false))
    }

    @Test
    fun `deletes not asked about are refused when the transaction ended before the submit ran`() {
        assertEquals(
            SubmitPlan.Refused("The transaction ended; submit again"),
            SubmitPlan.decide(CommitMode.AUTO, pendingTransaction = false, deletes = 2, asked = false),
        )
    }
}
