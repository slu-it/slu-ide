package dev.sluide.commandruns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class RunsTest {
    private val time = TestTimeSource()
    private val runs = Runs(time)

    @Test
    fun `the duration of a run is measured by the time source`() {
        runs.started()
        time += 90.seconds
        runs.ended(0)
        assertEquals(90.seconds, assertIs<RunState.Finished>(runs.state).duration)
        runs.started()
        time += 3.seconds
        runs.ended(2)
        assertEquals(3.seconds, assertIs<RunState.Failed>(runs.state).duration)
    }

    @Test
    fun `the running state knows when it started`() {
        runs.started()
        time += 7.seconds
        assertEquals(7.seconds, assertIs<RunState.Running>(runs.state).startedAt.elapsedNow())
    }

    @Test
    fun `a request while idle starts at once`() {
        assertEquals(RunDecision.StartNow, runs.request())
        assertEquals(RunState.Idle, runs.state)
    }

    @Test
    fun `a request while running asks to stop the running job`() {
        runs.started()
        assertEquals(RunDecision.AskToStop, runs.request())
    }

    @Test
    fun `a run that ends with zero finished`() {
        runs.started()
        runs.ended(0)
        assertIs<RunState.Finished>(runs.state)
        assertEquals(RunDecision.StartNow, runs.request())
    }

    @Test
    fun `a run that ends with another code failed with it`() {
        runs.started()
        runs.ended(1)
        assertEquals(1, assertIs<RunState.Failed>(runs.state).exitCode)
    }

    @Test
    fun `a run the user stopped counts as stopped whatever its exit code`() {
        runs.started()
        runs.stopRequested()
        runs.ended(130)
        assertEquals(RunState.Stopped, runs.state)
    }

    @Test
    fun `a stop request does not carry over to the next run`() {
        runs.stopRequested()
        runs.started()
        runs.ended(1)
        assertIs<RunState.Failed>(runs.state)
        runs.started()
        runs.stopRequested()
        runs.ended(1)
        runs.started()
        runs.ended(0)
        assertIs<RunState.Finished>(runs.state)
    }

    @Test
    fun `ending without a run changes nothing`() {
        runs.ended(1)
        assertEquals(RunState.Idle, runs.state)
    }

    private class FakeScheduler : StopScheduler {
        var delay: Duration? = null
        var action: (() -> Unit)? = null
        var cancelled = false

        override fun schedule(
            delay: Duration,
            action: () -> Unit,
        ): () -> Unit {
            this.delay = delay
            this.action = action
            return { cancelled = true }
        }
    }

    private val scheduler = FakeScheduler()
    private val calls = mutableListOf<String>()

    private fun escalation(grace: Duration = 5.seconds) =
        StopEscalation({ calls += "interrupt" }, { calls += "kill" }, scheduler, grace)

    @Test
    fun `stopping interrupts at once and kills after the grace`() {
        val stop = escalation(3.seconds)
        stop.stop()
        assertEquals(listOf("interrupt"), calls)
        assertEquals(3.seconds, scheduler.delay)
        scheduler.action!!()
        assertEquals(listOf("interrupt", "kill"), calls)
    }

    @Test
    fun `the grace is five seconds by default`() {
        escalation().stop()
        assertEquals(5.seconds, scheduler.delay)
    }

    @Test
    fun `a run that ends in time is not killed`() {
        val stop = escalation()
        stop.stop()
        stop.ended()
        assertEquals(true, scheduler.cancelled)
        scheduler.action!!()
        assertEquals(listOf("interrupt"), calls)
    }

    @Test
    fun `a second stop while one is pending does not interrupt again`() {
        val stop = escalation()
        stop.stop()
        stop.stop()
        assertEquals(listOf("interrupt"), calls)
    }

    @Test
    fun `after the run ended a new stop escalates again`() {
        val stop = escalation()
        stop.stop()
        stop.ended()
        stop.stop()
        assertEquals(listOf("interrupt", "interrupt"), calls)
    }
}
