package dev.sluide.speech.ui

import dev.sluide.speech.engine.SetupProblem
import kotlin.test.Test
import kotlin.test.assertEquals

class SetupNoticeTextTest {
    @Test
    fun `a missing tool names the tool and the hint without backticks`() {
        val problem = SetupProblem.ToolMissing("espeak-ng", "Install it with `brew install espeak-ng` (macOS).")

        assertEquals("espeak-ng not found: Install it with brew install espeak-ng (macOS).", toolMissingText(problem))
    }

    @Test
    fun `the download button shows the size in megabytes`() {
        assertEquals("Download (326 MB)", downloadLabel(MODEL_MISSING))
    }
}
