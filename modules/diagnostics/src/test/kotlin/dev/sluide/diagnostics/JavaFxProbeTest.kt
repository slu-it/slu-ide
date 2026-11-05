package dev.sluide.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

class JavaFxProbeTest {
    private val fx = ArrayDeque<Runnable>()
    private var now = 0L
    private val probe = JavaFxProbe(dispatch = { fx += it }, clock = { now })

    /** Lets [millis] pass, then runs what waits for the JavaFX thread. */
    private fun fxRunsAfter(millis: Long) {
        now += millis * 1_000_000
        while (fx.isNotEmpty()) fx.removeFirst().run()
    }

    @Test
    fun `records how long pings waited and which were not answered`() {
        probe.begin()
        probe.ping()
        fxRunsAfter(5)
        now += 5_000_000
        probe.ping()
        now += 20_000_000

        val result = probe.end()

        assertEquals(listOf(5_000_000L), result.latencies)
        assertEquals(1, result.unanswered)
        assertEquals(20_000_000, result.oldestUnansweredNanos)
        assertEquals(5_000_000, result.installedAfterNanos)
        // Answered after the end: not counted.
        fxRunsAfter(1)
        assertEquals(listOf(5_000_000L), result.latencies)
    }

    @Test
    fun `says when the counters were never installed`() {
        probe.begin()
        probe.ping()
        now += 10_000_000

        val result = probe.end()
        fxRunsAfter(1)

        assertEquals(null, result.installedAfterNanos)
        assertEquals(1, result.unanswered)
    }

    @Test
    fun `without a running JavaFX it measures nothing`() {
        val real = JavaFxProbe()

        real.begin()
        real.ping()

        assertEquals(FxResponsiveness(unavailable = "JavaFX is not running"), real.end())
    }
}
