package dev.sluide.docker.tab

import dev.sluide.docker.EngineState
import dev.sluide.docker.model.EngineFlavor
import dev.sluide.docker.model.EngineInfo
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A size as Docker prints it, in powers of 1000: `126 B`, `25.4 MB`, `1.2 GB`. */
internal fun humanSize(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("kB", "MB", "GB", "TB", "PB")
    var value = bytes / 1000.0
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}

/** How long ago [then] was at [now], roughly: "just now", "5 minutes ago", "3 days ago". */
internal fun relativeTime(
    then: Instant,
    now: Instant,
): String {
    val seconds = Duration.between(then, now).seconds

    fun ago(
        count: Long,
        unit: String,
    ) = "$count $unit${if (count == 1L) "" else "s"} ago"
    return when {
        seconds < 0 -> "in the future"
        seconds < 60 -> "just now"
        seconds < 3600 -> ago(seconds / 60, "minute")
        seconds < 86_400 -> ago(seconds / 3600, "hour")
        seconds < 86_400 * 60 -> ago(seconds / 86_400, "day")
        seconds < 86_400 * 730 -> ago(seconds / (86_400 * 30), "month")
        else -> ago(seconds / (86_400 * 365), "year")
    }
}

private val absoluteFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** [time] in the local zone (or [zone]), e.g. `2026-11-01 13:44:03`. */
internal fun absoluteTime(
    time: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String = absoluteFormat.format(time.atZone(zone))

/** The engine as the tab's header names it: "Docker 29.8.1 · context default", "Podman 5.2.0". */
internal fun engineText(engine: EngineInfo): String {
    val flavor = if (engine.flavor == EngineFlavor.PODMAN) "Podman" else "Docker"
    val context = engine.context?.takeIf { engine.flavor == EngineFlavor.DOCKER }?.let { " · context $it" } ?: ""
    return "$flavor ${engine.serverVersion}$context"
}

/** What the tab shows instead of the tree for an engine state, `null` for the tree. */
internal data class EngineNote(
    val message: String,
    /** Whether the note offers Retry; the quiet "Connecting…" note does not. */
    val retry: Boolean,
)

internal fun engineNote(state: EngineState): EngineNote? =
    when (state) {
        is EngineState.Available -> null
        EngineState.Idle, EngineState.Checking -> EngineNote("Connecting to Docker…", retry = false)
        is EngineState.Unavailable -> EngineNote(state.message, retry = true)
        is EngineState.NotFound -> EngineNote(state.message, retry = true)
    }
