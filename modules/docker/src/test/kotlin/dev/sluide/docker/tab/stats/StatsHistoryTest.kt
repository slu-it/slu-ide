package dev.sluide.docker.tab.stats

import dev.sluide.docker.model.StatsSample
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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

    private fun values(rates: Rates) =
        listOf(rates.networkReceive, rates.networkSend, rates.blockRead, rates.blockWrite)

    private fun counterSample(
        counter: Int,
        value: Long,
    ) = StatsSample(
        0.0,
        0,
        1000,
        if (counter == 0) value else 0,
        if (counter == 1) value else 0,
        if (counter == 2) value else 0,
        if (counter == 3) value else 0,
    )

    @Test
    fun `each rate comes from its own counter`() {
        val before = StatsSample(0.0, 0, 0, 1, 2, 3, 4)
        val after = StatsSample(0.0, 0, 0, 11, 32, 63, 124)

        assertEquals(Rates(5.0, 15.0, 30.0, 60.0), ratesBetween(before, after, 2.0))
    }

    @Test
    fun `every series gets its value of each sample`() {
        val history = StatsHistory()
        history.add(StatsSample(5.0, 100, 1000, 1000, 100, 200, 300), 0)
        history.add(StatsSample(6.0, 200, 1000, 3000, 700, 1000, 1300), 2_000_000_000)

        assertEquals(listOf(5.0, 6.0), history.cpu.values)
        assertEquals(listOf(100.0, 200.0), history.memory.values)
        assertEquals(listOf(1000.0), history.networkReceive.values)
        assertEquals(listOf(300.0), history.networkSend.values)
        assertEquals(listOf(400.0), history.blockRead.values)
        assertEquals(listOf(500.0), history.blockWrite.values)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3])
    fun `a reset of any one counter restarts the average`(counter: Int) {
        val history = StatsHistory()
        history.add(counterSample(counter, 5_000_000), 0)
        history.add(counterSample(counter, 6_000_000), 1_000_000_000)
        assertEquals(1_000_000.0, history.rates?.let(::values)?.get(counter), "rising: a rate")
        history.add(counterSample(counter, 100), 2_000_000_000)
        history.add(counterSample(counter, 2100), 3_000_000_000)

        assertEquals(2000.0, history.rates?.let(::values)?.get(counter))
    }

    @Test
    fun `the rate window holds 60 samples`() {
        val history = StatsHistory()

        // The counter steps once, at the 51st sample; one sample per second.
        fun add(second: Int) {
            val received = if (second < 50) 0L else 1_000_000L
            history.add(sample(received = received), second * 1_000_000_000L)
        }
        (0..59).forEach(::add)
        assertEquals(1_000_000.0 / 59, history.rates?.networkReceive, "60 samples span 59 s")
        add(60)
        assertEquals(1_000_000.0 / 59, history.rates?.networkReceive, "the oldest sample left the window")
    }

    @Test
    fun `clear also forgets the samples the rates are averaged over`() {
        val history = StatsHistory()
        history.add(sample(received = 1000), 0)
        history.add(sample(received = 3000), 2_000_000_000)
        history.clear()
        history.add(sample(received = 3500), 3_000_000_000)

        assertNull(history.rates, "one sample since the clear")
        assertEquals(emptyList(), history.networkReceive.values)
        assertEquals(emptyList(), history.networkSend.values)
        assertEquals(emptyList(), history.blockRead.values)
        assertEquals(emptyList(), history.blockWrite.values)
    }

    @Test
    fun `a sparkline of capacity 1 puts its value at the right edge`() {
        assertEquals(listOf(40.0, 10.0), sparklinePoints(listOf(5.0), 1, 40.0, 20.0, 10.0))
    }

    @Test
    fun `bytes round to whole numbers from 10, and stop at PiB`() {
        assertEquals("5 B", formatBytes(5L))
        assertEquals("9.5 KiB", formatBytes(9.5 * 1024))
        assertEquals("10 KiB", formatBytes(10.0 * 1024))
        assertEquals("2048 PiB", formatBytes(2048.0 * 1024 * 1024 * 1024 * 1024 * 1024))
    }
}
