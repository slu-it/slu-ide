package dev.sluide.diagnostics

import java.nio.file.Path
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Threads listed by CPU time. */
internal const val TOP_THREADS = 15

/** CPU in % of one core above which the stacks of a thread's native waits are shown: it may work there. */
internal const val NATIVE_WORK_PERCENT = 5.0

/** Everything `report.txt` shows. */
internal data class Report(
    val time: ZonedDateTime,
    val folder: Path,
    /** Lines from the caller: version, Java, JavaFX, OS, paths. */
    val info: List<Pair<String, String>>,
    val windowMillis: Long,
    val intervalMillis: Long,
    val pingMillis: Long,
    /** How long it sampled when it was cancelled early, else null. */
    val cancelledAfterNanos: Long?,
    /** The file name of the JFR recording in [folder], or null with [jfrMissing] saying why. */
    val jfr: String?,
    val jfrMissing: String?,
    /** How far back the JFR recording reaches at most. */
    val jfrMaxAge: Duration,
    /** The file name of the dump of all threads (virtual ones too) in [folder], or null with [threadsMissing]. */
    val threads: String?,
    val threadsMissing: String?,
    val sampling: SamplingResult,
    val fx: FxResponsiveness,
    val log: LogTail,
)

/** The end of the log file, or why there is none. */
internal data class LogTail(
    val file: Path?,
    val lines: List<String>,
    val problem: String? = null,
)

private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT)

/** The text of `report.txt`: plain text, sections under underlined headings, about 120 columns wide. */
internal fun reportText(report: Report): String =
    buildString {
        header(report)
        summary(report)
        threads(report.sampling)
        hotStacks(report.sampling)
        threadDump(report.sampling)
        log(report.log)
    }

private fun StringBuilder.heading(title: String) {
    append('\n')
        .append(title)
        .append('\n')
        .append("-".repeat(title.length))
        .append('\n')
}

private fun StringBuilder.field(
    label: String,
    value: String,
) {
    append(String.format(Locale.ROOT, "%-20s %s", if (label.isEmpty()) "" else "$label:", value)).append('\n')
}

/** A field with one value per line, so long lists stay narrow; [none] when there are no values. */
private fun StringBuilder.field(
    label: String,
    values: List<String>,
    none: String,
) {
    if (values.isEmpty()) return field(label, none)
    field(label, values.first())
    for (value in values.drop(1)) field("", value)
}

private fun StringBuilder.header(report: Report) {
    append("Thoreau diagnostics\n===================\n")
    field("Time", TIME.format(report.time))
    field("Folder", report.folder.toString())
    for ((label, value) in report.info) field(label, value)
    val sampling = report.sampling
    field(
        "Sampling",
        "${seconds(sampling.wallNanos)} of ${seconds(report.windowMillis * 1_000_000)}, stacks every " +
            "${report.intervalMillis} ms (${sampling.samples} samples), JavaFX pings every ${report.pingMillis} ms",
    )
    field("", "(the sampling thread itself is left out)")
    report.cancelledAfterNanos?.let { append("Cancelled after ${seconds(it)}\n") }
    field(
        "JFR",
        report.jfr?.let { "$it (up to the last ${duration(report.jfrMaxAge)} before the capture ended)" }
            ?: "no JFR recording (${report.jfrMissing})",
    )
    field(
        "All threads",
        report.threads?.let { "$it (virtual threads too)" } ?: "no thread dump (${report.threadsMissing})",
    )
}

private fun StringBuilder.summary(report: Report) {
    heading("Summary")
    val sampling = report.sampling
    field(
        "Process CPU",
        sampling.processCpuNanos?.let { cpu ->
            val ofOne = percent(cpu, sampling.wallNanos)
            "${format1(ofOne)} % of one core, ${format1(ofOne / sampling.processors)} % of all " +
                "${sampling.processors} cores"
        } ?: "not available",
    )
    field(
        "Unattributed CPU",
        sampling.unattributedNanos?.let { "${it / 1_000_000} ms (GC, JIT compiler, JFR and other JVM threads)" }
            ?: "not available",
    )
    field("Sampling thread CPU", sampling.samplerCpuNanos?.let { "${it / 1_000_000} ms" } ?: "not available")
    fx(report.fx)
    val heap = sampling.heap
    val max = if (heap.max < 0) "undefined" else mb(heap.max)
    field("Heap", "used ${mb(heap.used)}, committed ${mb(heap.committed)}, max $max")
    field("GC in the window", sampling.gc.map { "${it.name}: ${it.count} collections, ${it.millis} ms" }, "none")
    field("Deadlocks", sampling.deadlocked.joinToString(", ").ifEmpty { "none" })
}

private fun StringBuilder.fx(fx: FxResponsiveness) {
    if (fx.unavailable != null) {
        field("JavaFX", "not available (${fx.unavailable})")
        return
    }
    val pings = fx.latencies.size + fx.unanswered
    val latency =
        if (fx.latencies.isEmpty()) {
            "no ping answered"
        } else {
            val sorted = fx.latencies.sorted()
            "median ${millis(sorted[sorted.size / 2])}, max ${millis(sorted.last())}"
        }
    val unanswered =
        if (fx.unanswered == 0) {
            "${count(pings, "ping")}, all answered"
        } else {
            "${fx.unanswered} of $pings pings unanswered, the oldest waiting ${millis(fx.oldestUnansweredNanos)}"
        }
    field("JavaFX latency", "$latency; $unanswered")
    val installed = fx.installedAfterNanos
    when {
        installed == null -> {
            field("Pulses per second", "not measured: the JavaFX thread never got to install the counters")
        }

        fx.windows.isEmpty() -> {
            field("Pulses per second", "no window was showing")
        }

        else -> {
            field(
                "Pulses per second",
                fx.windows.map { "${it.label}: ${format1(if (it.nanos <= 0) 0.0 else it.pulses * 1e9 / it.nanos)}" },
                "",
            )
            field("", "(includes about 1/s from this capture's own progress row)")
        }
    }
    // Counters installed this late say the JavaFX thread was busy when the capture began.
    if (installed != null && installed >= 500_000_000) {
        field("", "counters installed ${millis(installed)} after the start (JavaFX thread busy)")
    }
}

private fun StringBuilder.threads(sampling: SamplingResult) {
    val count = sampling.threads.size
    heading("Threads by CPU in the window (${if (count > TOP_THREADS) "top $TOP_THREADS of $count" else "$count"})")
    append("CPU of ForkJoinPool-* (carrier) threads may be virtual-thread work: see threads.txt and recording.jfr.\n\n")
    append(String.format(Locale.ROOT, "%10s %7s  %-14s %s%n", "CPU ms", "% core", "State", "Thread"))
    for (thread in sampling.threads.take(TOP_THREADS)) {
        append(
            String.format(
                Locale.ROOT,
                "%10d %7s  %-14s %s%n",
                thread.cpuNanos / 1_000_000,
                format1(percent(thread.cpuNanos, sampling.wallNanos)),
                thread.state?.name ?: "ended",
                thread.name,
            ),
        )
    }
}

private fun StringBuilder.hotStacks(sampling: SamplingResult) {
    heading("Hot stacks (most frequent running or blocked stacks of the $HOT_THREADS busiest threads and JavaFX)")
    for (thread in sampling.hot) {
        val cpu = thread.cpuNanos?.let { "${it / 1_000_000} ms CPU" } ?: "no CPU time"
        append('\n')
            .append(thread.name)
            .append(" (")
            .append(cpu)
            .append(")\n")
        val samples = thread.samples
        if (thread.nestedLoops > 0) {
            append("  in a nested event loop (modal dialog open) in ${share(thread.nestedLoops, samples)}\n")
        }
        if (thread.nativeWaits > 0) {
            append("  in the native event loop or a selector wait in ${share(thread.nativeWaits, samples)}\n")
        }
        if (thread.probeSamples > 0) {
            append("  ${thread.probeSamples} of $samples samples answering this capture's pings\n")
        }
        if (thread.active + thread.nativeWaits + thread.nestedLoops + thread.probeSamples == 0) {
            append("  waiting in all $samples samples\n")
        }
        for (stack in thread.stacks) {
            append("  ${stack.count} of $samples samples running or blocked:\n")
            frames(stack.frames, stack.cut)
        }
        // A thread that used CPU while it seemed to wait in native code may work there: its native stacks show where.
        if (percent(thread.cpuNanos ?: 0, sampling.wallNanos) >= NATIVE_WORK_PERCENT) {
            for (stack in thread.waitStacks) {
                append("  ${stack.count} of $samples samples in native code, with CPU used in the window:\n")
                frames(stack.frames, stack.cut)
            }
        }
    }
}

private fun StringBuilder.threadDump(sampling: SamplingResult) {
    heading("Full thread dump (${sampling.finalDump.size} threads)")
    for (thread in sampling.finalDump.sortedBy { it.name.lowercase() }) {
        append('\n')
            .append('"')
            .append(thread.name)
            .append("\" #")
            .append(thread.id)
        if (thread.daemon) append(" daemon")
        append(' ').append(thread.state.name).append('\n')
        thread.lock?.let { lock ->
            append("  waiting for ").append(lock)
            thread.lockOwner?.let { append(" held by \"").append(it).append('"') }
            append('\n')
        }
        val shown = limitRuns(thread.stack, DUMP_LINES)
        frames(shown, thread.stack.size >= DUMP_DEPTH || shown.size < thread.stack.size)
    }
}

/** Prints [frames], a run of identical consecutive frames once with "(× n)"; [cut] says the stack went on. */
private fun StringBuilder.frames(
    frames: List<StackTraceElement>,
    cut: Boolean,
) {
    if (frames.isEmpty()) append("    (no Java frames)\n")
    var i = 0
    while (i < frames.size) {
        var end = i + 1
        while (end < frames.size && frames[end] == frames[i]) end++
        val frame = frames[i]
        append("    at ")
            .append(frame.className)
            .append('.')
            .append(frame.methodName)
            .append(where(frame))
        if (end - i > 1) append(" (× ").append(end - i).append(')')
        append('\n')
        i = end
    }
    if (cut) append("    … (cut)\n")
}

private fun StringBuilder.log(log: LogTail) {
    when {
        log.file == null -> {
            heading("Log")
            append("no log file\n")
        }

        log.problem != null -> {
            heading("Log ${log.file}")
            append("could not be read: ").append(log.problem).append('\n')
        }

        else -> {
            heading("Log (last ${log.lines.size} lines of ${log.file})")
            for (line in log.lines) append(line).append('\n')
        }
    }
}

/** Where a frame is, as in a stack trace, without the module and class loader `StackTraceElement` adds. */
private fun where(frame: StackTraceElement) =
    when {
        frame.isNativeMethod -> "(Native Method)"
        frame.fileName == null -> "(Unknown Source)"
        frame.lineNumber >= 0 -> "(${frame.fileName}:${frame.lineNumber})"
        else -> "(${frame.fileName})"
    }

private fun duration(duration: Duration) =
    if (duration.toSecondsPart() == 0) "${duration.toMinutes()} min" else "${duration.toSeconds()} s"

private fun share(
    n: Int,
    of: Int,
) = if (n == of) "all $of samples" else "$n of $of samples"

private fun count(
    n: Int,
    noun: String,
) = if (n == 1) "1 $noun" else "$n ${noun}s"

private fun percent(
    nanos: Long,
    of: Long,
) = if (of <= 0) 0.0 else nanos * 100.0 / of

private fun format1(value: Double) = String.format(Locale.ROOT, "%.1f", value)

private fun millis(nanos: Long) = "${nanos / 1_000_000} ms"

private fun seconds(nanos: Long) = String.format(Locale.ROOT, "%.1f s", nanos / 1e9)

private fun mb(bytes: Long) = "${bytes / (1024 * 1024)} MB"
