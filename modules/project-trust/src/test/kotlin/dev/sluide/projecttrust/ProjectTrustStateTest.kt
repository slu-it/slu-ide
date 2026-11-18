package dev.sluide.projecttrust

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectTrustStateTest {
    private val clean = TrustCheckResult("a", "A", emptyList())
    private val found = TrustCheckResult("b", "B", listOf(TrustFinding("runs x")))
    private val unchecked = TrustCheckResult("c", "C", null)
    private val all = listOf(clean, found, unchecked)

    @Test
    fun `no folder open and checking have no results`() {
        assertEquals(emptyList(), ProjectTrustState.Closed.results)
        assertEquals(emptyList(), ProjectTrustState.Closed.flagged)
        assertEquals(emptyList(), ProjectTrustState.Checking.results)
        assertEquals(emptyList(), ProjectTrustState.Checking.flagged)
    }

    @Test
    fun `a state with results flags those that found something or could not check`() {
        val states =
            listOf(
                ProjectTrustState.Trusted(all),
                ProjectTrustState.Ask(all, changed = false),
                ProjectTrustState.Untrusted(all),
            )

        for (state in states) {
            assertEquals(all, state.results)
            assertEquals(listOf(found, unchecked), state.flagged)
        }
    }
}
