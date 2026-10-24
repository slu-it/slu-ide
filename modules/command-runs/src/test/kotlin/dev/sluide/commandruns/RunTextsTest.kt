package dev.sluide.commandruns

import dev.sluide.fileviewers.ViewActivity
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class RunTextsTest {
    private data class Job(
        override val label: String,
    ) : RunJob {
        override val backgroundName = "Job: $label"
    }

    private val test = Job("test")

    @ParameterizedTest
    @CsvSource("0, 0:00", "999, 0:00", "42000, 0:42", "63000, 1:03", "723000, 12:03", "3723000, 1:02:03")
    fun `elapsed times are minutes and seconds, with hours once there are any`(
        millis: Long,
        text: String,
    ) {
        assertEquals(text, elapsedText(millis.milliseconds))
    }

    @Test
    fun `the default status line names the job and how it went`() {
        assertEquals("▶ test — running 0:42", test.runningText(42.seconds))
        assertEquals("✔ test — finished in 1:03", test.endedText(RunState.Finished(63.seconds)))
        assertEquals("✖ test — failed (exit 1) after 0:12", test.endedText(RunState.Failed(1, 12.seconds)))
        assertEquals("■ test — stopped", test.endedText(RunState.Stopped))
        assertNull(test.endedText(RunState.Running(TimeSource.Monotonic.markNow())))
        assertNull(test.endedText(RunState.Idle))
    }

    @Test
    fun `colours, their style classes and tab markers follow the state, a failure marks the tab`() {
        val states =
            listOf(
                RunState.Running(TimeSource.Monotonic.markNow()),
                RunState.Finished(1.seconds),
                RunState.Failed(2, 1.seconds),
                RunState.Stopped,
                RunState.Idle,
            )
        assertEquals(
            listOf(RunStatus.RUNNING, RunStatus.FINISHED, RunStatus.FAILED, RunStatus.STOPPED, null),
            states.map(::statusOf),
        )
        assertEquals(
            listOf("run-status-running", "run-status-finished", "run-status-failed", "run-status-stopped"),
            RunStatus.entries.map(RunPane.STATUS_STYLES::getValue),
        )
        assertEquals(
            listOf(ViewActivity.RUNNING, ViewActivity.NONE, ViewActivity.FAILED, ViewActivity.NONE, ViewActivity.NONE),
            states.map(::activityOf),
        )
    }

    @Test
    fun `the default stop question and close warning name the jobs`() {
        assertEquals("test is still running. Stop it and run build?", stopQuestion(test, Job("build")))
        assertEquals("Thoreau.sh is still running test.", test.closeWarning("Thoreau.sh"))
    }
}
