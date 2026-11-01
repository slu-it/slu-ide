package dev.sluide.docker.tab.logs

import java.time.Instant

/**
 * One line of the view. [seq] counts the lines of a buffer from 0; a [note] is the view's own line (container
 * stopped, failure), shown dimmed and never filtered out.
 */
internal data class LogLine(
    val seq: Long,
    val time: Instant?,
    val text: String,
    val note: Boolean = false,
)

/** The most recent lines, [capacity] at most: appending to a full buffer drops the oldest. */
internal class LogBuffer(
    private val capacity: Int = MAX_LOG_LINES,
) {
    private val lines = ArrayDeque<LogLine>()
    private var next = 0L

    val size: Int get() = lines.size

    /** The [LogLine.seq] of the oldest line kept; lines below it are gone. */
    val firstSeq: Long get() = next - lines.size

    fun append(
        time: Instant?,
        text: String,
        note: Boolean = false,
    ): LogLine {
        val line = LogLine(next++, time, text, note)
        lines.addLast(line)
        if (lines.size > capacity) lines.removeFirst()
        return line
    }

    /** The lines kept that pass [filter]; notes always do. */
    fun filtered(filter: String): List<LogLine> = lines.filter { it.note || matchesFilter(it.text, filter) }

    fun clear() {
        lines.clear()
    }
}

internal const val MAX_LOG_LINES = 50_000
