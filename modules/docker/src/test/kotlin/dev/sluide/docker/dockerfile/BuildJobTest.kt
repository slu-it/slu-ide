package dev.sluide.docker.dockerfile

import dev.sluide.commandruns.RunState
import dev.sluide.commandruns.stopQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** How a build names itself in the status line, the stop question, the task row and the close warning. */
class BuildJobTest {
    private val job = BuildJob("build myapp:1.0", "Dockerfile")

    @Test
    fun `the status line names the build`() {
        assertEquals("▶ build myapp:1.0 — running 0:02", job.runningText(2.seconds))
        assertEquals("✔ build myapp:1.0 — finished in 0:03", job.endedText(RunState.Finished(3.seconds)))
        assertEquals("✖ build myapp:1.0 — failed (exit 2) after 1:05", job.endedText(RunState.Failed(2, 65.seconds)))
        assertEquals("■ build myapp:1.0 — stopped", job.endedText(RunState.Stopped))
        assertNull(job.endedText(RunState.Idle))
    }

    @Test
    fun `the stop question, the task row and the close warning name the build and the file`() {
        assertEquals("build myapp:1.0", job.stopSubject)
        assertEquals("run build myapp:1.0", job.stopAction)
        assertEquals("Build: build myapp:1.0 (Dockerfile)", job.backgroundName)
        assertEquals("Dockerfile is still running build myapp:1.0.", job.closeWarning("Dockerfile"))
        assertEquals(
            "build myapp:1.0 is still running. Stop it and run build Dockerfile?",
            stopQuestion(job, BuildJob("build Dockerfile", "Dockerfile")),
        )
    }
}
