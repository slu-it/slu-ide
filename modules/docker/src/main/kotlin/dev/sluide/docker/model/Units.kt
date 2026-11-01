package dev.sluide.docker.model

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.roundToLong

private val sizePattern = Regex("""^\s*([0-9]+(?:\.[0-9]+)?)\s*([a-zA-Z]*)\s*$""")

private val unitFactors =
    mapOf(
        "" to 1.0,
        "b" to 1.0,
        "k" to 1e3,
        "kb" to 1e3,
        "m" to 1e6,
        "mb" to 1e6,
        "g" to 1e9,
        "gb" to 1e9,
        "t" to 1e12,
        "tb" to 1e12,
        "p" to 1e15,
        "pb" to 1e15,
        "kib" to 1024.0,
        "mib" to 1024.0 * 1024,
        "gib" to 1024.0 * 1024 * 1024,
        "tib" to 1024.0 * 1024 * 1024 * 1024,
        "pib" to 1024.0 * 1024 * 1024 * 1024 * 1024,
    )

/**
 * The bytes of a size as the CLIs print it: `126B`, `1.24kB`, `25.4MB` (powers of 1000), `10.84MiB` (powers of 1024),
 * or a plain number. `null` for anything else, such as `N/A` or `--`.
 */
fun parseSize(text: String?): Long? {
    val match = sizePattern.matchEntire(text ?: return null) ?: return null
    val factor = unitFactors[match.groupValues[2].lowercase()] ?: return null
    return (match.groupValues[1].toDouble() * factor).roundToLong()
}

/** Both sizes of a pair such as `10.84MiB / 11.84GiB` (memory) or `1.17kB / 126B` (network, IO); 0 if unreadable. */
fun parseSizePair(text: String?): Pair<Long, Long> {
    val parts = text?.split("/") ?: return 0L to 0L
    if (parts.size != 2) return 0L to 0L
    return (parseSize(parts[0]) ?: 0L) to (parseSize(parts[1]) ?: 0L)
}

/** The number of a percentage such as `0.28%`; `null` if unreadable (`--`). */
fun parsePercent(text: String?): Double? =
    text
        ?.trim()
        ?.removeSuffix("%")
        ?.trim()
        ?.toDoubleOrNull()

private val dockerTimePattern =
    Regex("""^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})(\.\d+)? ([+-]\d{2})(\d{2})(?: \S+)?$""")

/**
 * The time of a CLI timestamp: Docker's `2026-11-01 13:44:03 +0000 UTC` (with or without fractions), RFC 3339
 * (`2026-11-01T13:44:03.42Z`, inspect and Podman), or Unix seconds. `null` for anything else and for Go's zero time
 * (`0001-01-01T00:00:00Z`, "never").
 */
fun parseTime(text: String?): Instant? {
    val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    value.toLongOrNull()?.let { return epochSeconds(it) }
    val instant =
        dockerTimePattern.matchEntire(value)?.let { match ->
            val (date, time, fraction, hours, minutes) = match.destructured
            parseOffset("${date}T$time$fraction$hours:$minutes")
        } ?: parseOffset(value)
    return instant?.takeIf { it.isAfter(ZERO_TIME) }
}

/** The time of Unix [seconds]; `null` for zero and negative values, which the CLIs print for "never". */
fun epochSeconds(seconds: Long): Instant? = if (seconds > 0) Instant.ofEpochSecond(seconds) else null

private val ZERO_TIME = Instant.parse("0001-01-02T00:00:00Z")

private fun parseOffset(text: String): Instant? =
    try {
        OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }
