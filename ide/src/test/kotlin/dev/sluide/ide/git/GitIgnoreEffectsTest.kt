package dev.sluide.ide.git

import dev.sluide.fileviewers.IgnoreLineEffect
import dev.sluide.git.GitIgnoreLineUse
import dev.sluide.git.GitIgnoreRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GitIgnoreEffectsTest {
    @Test
    fun `git's 1-based lines become the editor's 0-based lines with their use`() {
        val rules =
            GitIgnoreRules(
                mapOf(
                    1 to GitIgnoreLineUse(2, 0, 1, listOf("a.log", "b.log"), listOf("kept.log")),
                    4 to GitIgnoreLineUse(0, 1, 0, listOf("keep.log"), emptyList()),
                ),
                complete = false,
            )

        val effects = rules.toEffects()

        assertEquals(
            mapOf(
                0 to IgnoreLineEffect(2, 0, 1, listOf("a.log", "b.log"), listOf("kept.log")),
                3 to IgnoreLineEffect(0, 1, 0, listOf("keep.log"), emptyList()),
            ),
            effects.lines,
        )
        assertFalse(effects.complete)
    }
}
