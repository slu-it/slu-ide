package dev.sluide.gradle.view

import dev.sluide.fileviewers.ViewActivity
import dev.sluide.gradle.run.RunState
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class RunTextsTest {
    private val test = GradleJob.Tasks(listOf(":app:test"))
    private val two = GradleJob.Tasks(listOf(":app:clean", ":app:build"))
    private val load = GradleJob.LoadTasks

    @ParameterizedTest
    @CsvSource("0, 0:00", "999, 0:00", "42000, 0:42", "63000, 1:03", "723000, 12:03", "3723000, 1:02:03")
    fun `elapsed times are minutes and seconds, with hours once there are any`(
        millis: Long,
        text: String,
    ) {
        assertEquals(text, elapsedText(millis.milliseconds))
    }

    @Test
    fun `the status line names the tasks, or the loading, and how it went`() {
        val running = RunState.Running(test.arguments, TimeSource.Monotonic.markNow())
        assertEquals("▶ :app:test — running 0:42", runningText(test, 42.seconds))
        assertEquals("▶ Loading tasks… 0:03", runningText(load, 3.seconds))
        assertEquals(
            "✔ :app:clean :app:build — finished in 1:03",
            endedText(two, RunState.Finished(two.arguments, 63.seconds)),
        )
        assertEquals(
            "✖ :app:test — failed (exit 1) after 0:12",
            endedText(test, RunState.Failed(test.arguments, 1, 12.seconds)),
        )
        assertEquals("■ :app:test — stopped", endedText(test, RunState.Stopped))
        assertEquals("✔ Tasks loaded in 0:05", endedText(load, RunState.Finished(emptyList(), 5.seconds)))
        assertEquals(
            "✖ Loading tasks failed (exit 1) after 0:02",
            endedText(load, RunState.Failed(emptyList(), 1, 2.seconds)),
        )
        assertEquals("■ Loading tasks stopped", endedText(load, RunState.Stopped))
        assertNull(endedText(test, running))
        assertNull(endedText(test, RunState.Idle))
    }

    @Test
    fun `styles and tab markers follow the state, a failure marks the tab`() {
        val states =
            listOf(
                RunState.Running(test.arguments, TimeSource.Monotonic.markNow()),
                RunState.Finished(test.arguments, 1.seconds),
                RunState.Failed(test.arguments, 2, 1.seconds),
                RunState.Stopped,
                RunState.Idle,
            )
        assertEquals(
            listOf(
                "gradle-status-running",
                "gradle-status-finished",
                "gradle-status-failed",
                "gradle-status-stopped",
                null,
            ),
            states.map(::statusStyle),
        )
        assertEquals(
            listOf(ViewActivity.RUNNING, ViewActivity.NONE, ViewActivity.FAILED, ViewActivity.NONE, ViewActivity.NONE),
            states.map(::activityOf),
        )
    }

    @Test
    fun `the stop question, the background row and the close warning name the jobs`() {
        assertEquals(
            ":app:test is still running. Stop it and run :app:build?",
            stopQuestion(test, GradleJob.Tasks(listOf(":app:build"))),
        )
        assertEquals("Loading the tasks is still running. Stop it and run :app:test?", stopQuestion(load, test))
        assertEquals(":app:test is still running. Stop it and load the tasks again?", stopQuestion(test, load))
        assertEquals(
            listOf("Gradle: :app:test", "Gradle: :app:clean :app:build", "Gradle: loading tasks"),
            listOf(test, two, load).map(::backgroundName),
        )
        assertEquals("build.gradle.kts is still running :app:test.", closeWarningText("build.gradle.kts", test))
        assertEquals("build.gradle is still loading tasks.", closeWarningText("build.gradle", load))
    }
}
