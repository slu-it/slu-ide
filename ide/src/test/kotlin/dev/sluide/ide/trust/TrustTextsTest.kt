package dev.sluide.ide.trust

import dev.sluide.projecttrust.ProjectTrustState
import dev.sluide.projecttrust.TrustCheckResult
import dev.sluide.projecttrust.TrustFinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrustTextsTest {
    private fun flagged(vararg names: String) =
        names.map { TrustCheckResult(it.lowercase(), it, listOf(TrustFinding("x = y"))) }

    private fun ask(
        vararg names: String,
        changed: Boolean = false,
    ) = ProjectTrustState.Ask(flagged(*names), changed)

    @Test
    fun `the tools that are off are named in a list`() {
        assertEquals("", toolsOff(ask()))
        assertEquals("Git", toolsOff(ask("Git")))
        assertEquals("Git and Build", toolsOff(ask("Git", "Build")))
        assertEquals("A, B and C", toolsOff(ask("A", "B", "C")))
    }

    @Test
    fun `the details list the findings that run first, then those not known, per tool`() {
        val state =
            ProjectTrustState.Ask(
                listOf(
                    TrustCheckResult(
                        "git",
                        "Git",
                        listOf(
                            TrustFinding("something.new = x (.git/config)"),
                            TrustFinding("core.fsmonitor = ./watch.sh (.git/config)", "runs on every git status"),
                        ),
                    ),
                    TrustCheckResult("build", "Build", null),
                ),
                changed = false,
            )

        assertEquals(
            """
            Git
              core.fsmonitor = ./watch.sh (.git/config)
                  runs on every git status
              something.new = x (.git/config)
                  not known to Thoreau

            Build
              could not be checked
            """.trimIndent(),
            trustDetails(state),
        )
    }

    @Test
    fun `the bar says why one tool is off`() {
        assertEquals("Git is off: this folder defines commands it would run.", trustBarMessage(ask("Git")))
        assertEquals(
            "Git is off: what this folder makes it run changed.",
            trustBarMessage(ask("Git", changed = true)),
        )
        assertEquals(
            "Git is off: this folder defines commands it would run.",
            trustBarMessage(ProjectTrustState.Untrusted(flagged("Git"))),
        )
    }

    @Test
    fun `the bar says they for several tools`() {
        assertEquals(
            "Git and Build are off: this folder defines commands they would run.",
            trustBarMessage(ask("Git", "Build")),
        )
        assertEquals(
            "Git and Build are off: what this folder makes them run changed.",
            trustBarMessage(ask("Git", "Build", changed = true)),
        )
    }

    @Test
    fun `the bar says when the folder could not be checked, and nothing while no tool is off`() {
        val unchecked = listOf(TrustCheckResult("git", "Git", null))
        assertEquals(
            "Git is off: this folder could not be checked.",
            trustBarMessage(ProjectTrustState.Untrusted(unchecked)),
        )
        assertNull(trustBarMessage(ProjectTrustState.Closed))
        assertNull(trustBarMessage(ProjectTrustState.Checking))
        assertNull(trustBarMessage(ProjectTrustState.Trusted(flagged("Git"))))
    }
}
