package dev.sluide.gradle.view

import dev.sluide.projecttrust.ProjectTrustState
import dev.sluide.projecttrust.TrustCheckResult
import dev.sluide.projecttrust.TrustFinding
import kotlin.test.Test
import kotlin.test.assertEquals

class TrustNoteTest {
    private val found = listOf(TrustCheckResult("gradle", "Gradle", listOf(TrustFinding("Gradle wrapper in ."))))

    @Test
    fun `checks running say so, an undecided or untrusted folder keeps Gradle off, trusted has no note`() {
        assertEquals(
            listOf(TaskNote.CHECKING, TaskNote.UNTRUSTED, TaskNote.UNTRUSTED, TaskNote.UNTRUSTED, null),
            listOf(
                ProjectTrustState.Checking,
                ProjectTrustState.Ask(found, changed = false),
                ProjectTrustState.Untrusted(found),
                ProjectTrustState.Closed,
                ProjectTrustState.Trusted(found),
            ).map(::trustNote),
        )
    }
}
