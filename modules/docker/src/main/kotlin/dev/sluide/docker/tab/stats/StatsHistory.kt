package dev.sluide.docker.tab.stats

import dev.sluide.docker.model.StatsSample
import java.util.Locale

/** How many samples the sparklines show. */
internal const val SPARKLINE_SAMPLES = 60

/** The last [capacity] values added, oldest first. */
internal class Ring(
    private val capacity: Int = SPARKLINE_SAMPLES,
) {
    private val items = ArrayDeque<Double>()

    val values: List<Double> get() = items.toList()

    fun add(value: Double) {
        items.addLast(value)
        if (items.size > capacity) items.removeFirst()
    }

    fun clear() = items.clear()
}

/** Bytes per second between two samples of the cumulative counters. */
internal data class Rates(
    val networkReceive: Double,
    val networkSend: Double,
    val blockRead: Double,
    val blockWrite: Double,
)

/** The rate of a counter that went from [before] to [after] in [seconds]; a counter that went down was reset: 0. */
internal fun counterRate(
    before: Long,
    after: Long,
    seconds: Double,
): Double = if (after < before || seconds <= 0.0) 0.0 else (after - before) / seconds

/** The rates from [before] to [after], [seconds] apart. */
internal fun ratesBetween(
    before: StatsSample,
    after: StatsSample,
    seconds: Double,
) = Rates(
    networkReceive = counterRate(before.networkReceived, after.networkReceived, seconds),
    networkSend = counterRate(before.networkSent, after.networkSent, seconds),
    blockRead = counterRate(before.blockRead, after.blockRead, seconds),
    blockWrite = counterRate(before.blockWritten, after.blockWritten, seconds),
)

/**
 * How many samples a rate is averaged over: the whole sparkline window. The CLI rounds the counters to three
 * significant digits, so they move in steps (above 1 GB in steps of 10 MB); the longer the window, the smaller the
 * error of those steps, but a rate below a step per window still reads 0 or one step.
 */
internal const val RATE_WINDOW = SPARKLINE_SAMPLES

/** The samples of one container: a ring per series, the last sample and the rates over the last [RATE_WINDOW]. */
internal class StatsHistory(
    capacity: Int = SPARKLINE_SAMPLES,
) {
    val cpu = Ring(capacity)
    val memory = Ring(capacity)
    val networkReceive = Ring(capacity)
    val networkSend = Ring(capacity)
    val blockRead = Ring(capacity)
    val blockWrite = Ring(capacity)

    var last: StatsSample? = null
        private set

    /** The rates over the last [RATE_WINDOW] samples; `null` until there are two. */
    var rates: Rates? = null
        private set
    private val window = ArrayDeque<Pair<StatsSample, Long>>()

    fun add(
        sample: StatsSample,
        atNanos: Long,
    ) {
        // A counter that went down was reset (container restarted, stats stream reopened): average from here on.
        if (window.lastOrNull()?.first?.let { counterWentDown(it, sample) } == true) window.clear()
        window.addLast(sample to atNanos)
        if (window.size > RATE_WINDOW) window.removeFirst()
        if (window.size > 1) {
            val (oldest, oldestAt) = window.first()
            ratesBetween(oldest, sample, (atNanos - oldestAt) / 1e9).also {
                rates = it
                networkReceive.add(it.networkReceive)
                networkSend.add(it.networkSend)
                blockRead.add(it.blockRead)
                blockWrite.add(it.blockWrite)
            }
        }
        last = sample
        cpu.add(sample.cpuPercent)
        memory.add(sample.memoryUsed.toDouble())
    }

    fun clear() {
        listOf(cpu, memory, networkReceive, networkSend, blockRead, blockWrite).forEach { it.clear() }
        last = null
        rates = null
        window.clear()
    }
}

private fun counterWentDown(
    before: StatsSample,
    after: StatsSample,
) = after.networkReceived < before.networkReceived ||
    after.networkSent < before.networkSent ||
    after.blockRead < before.blockRead ||
    after.blockWritten < before.blockWritten

/**
 * The points of a sparkline of [values] (x and y alternating, y down) in a box of [width] x [height]: the newest value
 * at the right edge, each older one a step of `width / (capacity - 1)` further left; a value of [max] is at the top, 0
 * at the bottom.
 */
internal fun sparklinePoints(
    values: List<Double>,
    capacity: Int,
    width: Double,
    height: Double,
    max: Double,
): List<Double> {
    val step = if (capacity > 1) width / (capacity - 1) else 0.0
    return values.flatMapIndexed { index, value ->
        val share = if (max > 0) (value / max).coerceIn(0.0, 1.0) else 0.0
        listOf(width - (values.size - 1 - index) * step, height - share * height)
    }
}

private val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")

/** [bytes] in binary units with two significant digits at least: `512 B`, `7.6 GiB`, `214 MiB`. */
internal fun formatBytes(bytes: Double): String {
    var value = bytes.coerceAtLeast(0.0)
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val digits = if (unit == 0 || value >= 10) 0 else 1
    return String.format(Locale.ROOT, "%.${digits}f %s", value, units[unit])
}

internal fun formatBytes(bytes: Long): String = formatBytes(bytes.toDouble())

internal fun formatRate(bytesPerSecond: Double): String = formatBytes(bytesPerSecond) + "/s"

internal fun formatPercent(percent: Double): String = String.format(Locale.ROOT, "%.1f %%", percent)

/** `214 MiB / 7.6 GiB (2.8 %)`; without a limit just the use. */
internal fun formatMemory(sample: StatsSample): String =
    if (sample.memoryLimit > 0) {
        "${formatBytes(sample.memoryUsed)} / ${formatBytes(sample.memoryLimit)} " +
            "(${formatPercent(sample.memoryUsed * 100.0 / sample.memoryLimit)})"
    } else {
        formatBytes(sample.memoryUsed)
    }
