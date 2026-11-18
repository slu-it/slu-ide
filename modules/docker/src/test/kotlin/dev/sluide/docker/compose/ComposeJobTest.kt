package dev.sluide.docker.compose

import dev.sluide.commandruns.RunState
import dev.sluide.commandruns.stopQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** How a compose command names itself in the status line, the stop question, the task row and the close warning. */
class ComposeJobTest {
    private val job = ComposeJob("up web", "compose.yaml")

    @Test
    fun `the status line names the verb and the service`() {
        assertEquals("▶ up web — running 0:02", job.runningText(2.seconds))
        assertEquals("✔ up web — finished in 0:03", job.endedText(RunState.Finished(3.seconds)))
        assertEquals("✖ up web — failed (exit 2) after 1:05", job.endedText(RunState.Failed(2, 65.seconds)))
        assertEquals("■ up web — stopped", job.endedText(RunState.Stopped))
        assertNull(job.endedText(RunState.Idle))
    }

    @Test
    fun `the stop question, the task row and the close warning name the command and the file`() {
        assertEquals("up web", job.stopSubject)
        assertEquals("run up web", job.stopAction)
        assertEquals("Compose: up web (compose.yaml)", job.backgroundName)
        assertEquals("compose.yaml is still running up web.", job.closeWarning("compose.yaml"))
        assertEquals(
            "up web is still running. Stop it and run down -v?",
            stopQuestion(job, ComposeJob("down -v", "compose.yaml")),
        )
    }
}
