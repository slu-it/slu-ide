package dev.sluide.docker.tab.stats

import dev.sluide.docker.model.StatsSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatsHistoryTest {
    private fun sample(
        cpu: Double = 0.0,
        received: Long = 0,
        sent: Long = 0,
        read: Long = 0,
        written: Long = 0,
    ) = StatsSample(cpu, 0, 1000, received, sent, read, written)

    @Test
    fun `a counter rate is the difference per second`() {
        assertEquals(50.0, counterRate(100, 200, 2.0))
    }

    @Test
    fun `a counter that went down was reset and counts 0`() {
        assertEquals(0.0, counterRate(500, 20, 1.0))
        assertEquals(0.0, counterRate(1, 2, 0.0))
    }

    @Test
    fun `rates come from the second sample on`() {
        val history = StatsHistory()
        history.add(sample(received = 1000, written = 10), 0)
        assertNull(history.rates)
        history.add(sample(received = 3000, sent = 500, read = 4096, written = 10), 2_000_000_000)
        assertEquals(Rates(1000.0, 250.0, 2048.0, 0.0), history.rates)
        assertEquals(listOf(1000.0), history.networkReceive.values)
    }

    @Test
    fun `a ring keeps the last 60 values`() {
        val ring = Ring()
        repeat(75) { ring.add(it.toDouble()) }
        assertEquals(60, ring.values.size)
        assertEquals(15.0, ring.values.first())
        assertEquals(74.0, ring.values.last())
    }

    @Test
    fun `clear forgets everything`() {
        val history = StatsHistory()
        history.add(sample(cpu = 5.0), 0)
        history.add(sample(cpu = 6.0), 1_000_000_000)
        history.clear()
        assertNull(history.last)
        assertNull(history.rates)
        assertEquals(emptyList(), history.cpu.values)
    }

    @Test
    fun `rates average over the last samples, so rounded counters do not jump`() {
        val history = StatsHistory()
        // The counter moves in one step of 1 000 000, at the sixth sample, as rounded counters do.
        repeat(7) { history.add(sample(received = if (it >= 5) 1_000_000L else 0L), it * 1_000_000_000L) }
        assertEquals(1_000_000.0 / 6, history.rates?.networkReceive, "not the spike of 1 000 000 per second")
        history.add(sample(received = 1_000_000), 7_000_000_000L)
        assertEquals(1_000_000.0 / 7, history.rates?.networkReceive)
    }

    @Test
    fun `a reset counter restarts the average`() {
        val history = StatsHistory()
        history.add(sample(received = 5_000_000), 0)
        history.add(sample(received = 6_000_000), 1_000_000_000)
        history.add(sample(received = 100), 2_000_000_000)
        history.add(sample(received = 2100), 3_000_000_000)
        assertEquals(2000.0, history.rates?.networkReceive)
    }

    @Test
    fun `sparkline points end at the right edge and scale to the maximum`() {
        val points = sparklinePoints(listOf(0.0, 50.0, 100.0, 200.0), 5, 40.0, 20.0, 100.0)
        assertEquals(listOf(10.0, 20.0, 20.0, 10.0, 30.0, 0.0, 40.0, 0.0), points)
    }

    @Test
    fun `sparkline of a zero maximum is flat at the bottom`() {
        assertEquals(listOf(30.0, 20.0, 40.0, 20.0), sparklinePoints(listOf(0.0, 0.0), 5, 40.0, 20.0, 0.0))
    }

    @Test
    fun `bytes are shown in binary units`() {
        assertEquals("512 B", formatBytes(512L))
        assertEquals("1.0 KiB", formatBytes(1024L))
        assertEquals("214 MiB", formatBytes(214L * 1024 * 1024))
        assertEquals("7.6 GiB", formatBytes((7.6 * 1024 * 1024 * 1024).toLong()))
        assertEquals("1.5 MiB/s", formatRate(1.5 * 1024 * 1024))
    }

    @Test
    fun `memory shows use, limit and share`() {
        val used = 214L * 1024 * 1024
        val limit = (7.6 * 1024 * 1024 * 1024).toLong()
        assertEquals("214 MiB / 7.6 GiB (2.7 %)", formatMemory(StatsSample(0.0, used, limit, 0, 0, 0, 0)))
        assertEquals("214 MiB", formatMemory(StatsSample(0.0, used, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `percent has one decimal`() {
        assertEquals("12.4 %", formatPercent(12.43))
    }
}
