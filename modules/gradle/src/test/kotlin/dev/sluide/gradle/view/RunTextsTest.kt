package dev.sluide.gradle.view

import dev.sluide.commandruns.RunState
import dev.sluide.commandruns.stopQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class RunTextsTest {
    private val test = GradleJob.Tasks(listOf(":app:test"))
    private val two = GradleJob.Tasks(listOf(":app:clean", ":app:build"))
    private val load = GradleJob.LoadTasks

    @Test
    fun `the status line of the load says how loading went`() {
        val running = RunState.Running(TimeSource.Monotonic.markNow())
        assertEquals("▶ Loading tasks… 0:03", load.runningText(3.seconds))
        assertEquals("✔ Tasks loaded in 0:05", load.endedText(RunState.Finished(5.seconds)))
        assertEquals("✖ Loading tasks failed (exit 1) after 0:02", load.endedText(RunState.Failed(1, 2.seconds)))
        assertEquals("■ Loading tasks stopped", load.endedText(RunState.Stopped))
        assertNull(load.endedText(running))
        assertNull(load.endedText(RunState.Idle))
    }

    @Test
    fun `the stop question and the close warning name the load, the background row names Gradle`() {
        assertEquals("Loading the tasks is still running. Stop it and run :app:test?", stopQuestion(load, test))
        assertEquals(":app:test is still running. Stop it and load the tasks again?", stopQuestion(test, load))
        assertEquals(
            listOf("Gradle: :app:test", "Gradle: :app:clean :app:build", "Gradle: loading tasks"),
            listOf(test, two, load).map { it.backgroundName },
        )
        assertEquals("build.gradle is still loading tasks.", load.closeWarning("build.gradle"))
    }
}
