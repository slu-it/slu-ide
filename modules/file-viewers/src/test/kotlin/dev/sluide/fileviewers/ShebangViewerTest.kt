package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShebangViewerTest {
    @Test
    fun `interpreterMatches ignores case and matches interpreters with and without their version`() {
        val interpreters = setOf("python", "python3", "Bash", "ksh")

        val matching =
            listOf(
                "python",
                "python3",
                "Python3",
                "python3.12",
                "python2",
                "python2.7",
                "bash",
                "BASH",
                "bash5",
                "ksh93",
            )
        for (interpreter in matching) {
            assertTrue(interpreterMatches(interpreter, interpreters), interpreter)
        }
        for (interpreter in listOf("pythonw", "ipython", "py", "zsh", "mksh", "sh")) {
            assertFalse(interpreterMatches(interpreter, interpreters), interpreter)
        }
    }
}
