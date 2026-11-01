package dev.sluide.docker.tab.logs

import dev.sluide.docker.model.parseTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ansi =
    Regex(
        "\u001B\\[[0-?]*[ -/]*[@-~]" + // CSI: colours (SGR), cursor movement, erase
            "|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)?" + // OSC: titles, hyperlinks
            "|\u001B[@-Z\\\\-_]", // other two-character escapes
    )
private val controls = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]")
private val timestamped = Regex("""^(\d{4}-\d{2}-\d{2}T\S+)(?: (.*))?$""", RegexOption.DOT_MATCHES_ALL)
private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/**
 * The text of a log line for display: terminal escape sequences (colours, cursor codes, titles) and other control
 * characters removed, and of a line with carriage returns (progress bars) only what follows the last one.
 */
internal fun cleanLogText(line: String): String =
    ansi
        .replace(line.trimEnd('\r'), "")
        .substringAfterLast('\r')
        .let { controls.replace(it, "") }

/** Docker's `--timestamps` prefix split off a [line]: its time (`null` if there is none) and the rest. */
internal fun splitTimestamp(line: String): Pair<Instant?, String> {
    val match = timestamped.matchEntire(line) ?: return null to line
    val time = parseTime(match.groupValues[1]) ?: return null to line
    return time to match.groupValues[2]
}

/** A log time as local `HH:mm:ss.SSS`. */
internal fun formatLogTime(
    time: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String = clock.withZone(zone).format(time)

/** Whether a line with [text] passes [filter]: a case-insensitive substring; an empty filter lets everything pass. */
internal fun matchesFilter(
    text: String,
    filter: String,
): Boolean = filter.isEmpty() || text.contains(filter, ignoreCase = true)

/** Whether the view stays at the end after the user scrolled: when its scroll bar is at [max] (or nearly). */
internal fun followsAfterScroll(
    position: Double,
    max: Double,
): Boolean = position >= max - 1e-3
