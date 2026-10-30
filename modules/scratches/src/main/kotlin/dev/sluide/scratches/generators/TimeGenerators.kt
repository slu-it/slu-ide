package dev.sluide.scratches.generators

import dev.sluide.scratches.GeneratorContext
import dev.sluide.scratches.GeneratorGroup
import dev.sluide.scratches.ScratchGenerator
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun pattern(text: String): DateTimeFormatter = DateTimeFormatter.ofPattern(text, Locale.ROOT)

private val ISO_MILLIS_UTC = pattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
private val ISO_MILLIS_OFFSET = pattern("uuuu-MM-dd'T'HH:mm:ss.SSSxxx")

// For an offset-only zone such as "Z" or "+01:00", VV prints the offset itself in brackets, e.g. "[Z]"; this is intended.
private val ISO_MILLIS_ZONE = pattern("uuuu-MM-dd'T'HH:mm:ss.SSSxxx'['VV']'")
private val LOCAL_DATE_TIME = pattern("uuuu-MM-dd HH:mm:ss")
private val SQL_TIMESTAMP = pattern("uuuu-MM-dd HH:mm:ss.SSS")
private val RFC_1123 = DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
private val DATE = pattern("uuuu-MM-dd")

/** The labelled lines of the current time from the context's clock, values aligned after the longest label. */
private fun currentTime(context: GeneratorContext): String {
    val now = context.clock.instant()
    val utc = now.atZone(ZoneOffset.UTC)
    val local = now.atZone(context.zone)
    val rows =
        listOf(
            "Epoch seconds" to now.epochSecond.toString(),
            "Epoch milliseconds" to now.toEpochMilli().toString(),
            "ISO-8601 UTC" to ISO_MILLIS_UTC.format(utc),
            "ISO-8601 local" to ISO_MILLIS_OFFSET.format(local),
            "ISO-8601 with zone" to ISO_MILLIS_ZONE.format(local),
            "RFC 1123 (HTTP)" to RFC_1123.format(utc),
            "Local date-time" to LOCAL_DATE_TIME.format(local),
            "SQL timestamp UTC" to SQL_TIMESTAMP.format(utc),
            "Date" to DATE.format(local),
            "ISO week date" to DateTimeFormatter.ISO_WEEK_DATE.format(local.toLocalDate()),
            "Ordinal date" to DateTimeFormatter.ISO_ORDINAL_DATE.format(local.toLocalDate()),
        )
    val width = rows.maxOf { it.first.length } + 2
    return rows.joinToString("") { (label, value) -> label.padEnd(width) + value + "\n" }
}

/** The Time entries of the generated scratch file picker (ADR 0114): the current time in many formats, one per line. */
internal val TIME_GENERATORS: List<ScratchGenerator> =
    listOf(ScratchGenerator(GeneratorGroup.TIME, "Current Time (all formats)", "time", generate = ::currentTime))
