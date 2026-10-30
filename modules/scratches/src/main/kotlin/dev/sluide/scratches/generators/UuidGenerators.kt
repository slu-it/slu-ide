package dev.sluide.scratches.generators

import dev.sluide.scratches.GeneratorContext
import dev.sluide.scratches.GeneratorGroup
import dev.sluide.scratches.ScratchGenerator
import dev.sluide.scratches.lines
import java.util.UUID

/** Seconds between 1582-10-15 (the UUID epoch) and 1970-01-01. */
private const val GREGORIAN_OFFSET_SECONDS = 12_219_292_800L
private const val TICKS_PER_SECOND = 10_000_000L
private const val COUNTER_LIMIT = 0x1000
private const val VARIANT_BITS = 0x2L shl 62
private const val NODE_MASK = 0xffff_ffff_ffffL
private const val MULTICAST_BIT = 1L shl 40

/**
 * The Uuid entries of the generated scratch file picker (ADR 0114): 50 lowercase UUIDs per file. v1 and v6 take the
 * time once, step it by one 100-ns tick per UUID and use a random clock sequence and a random multicast node (never
 * the real MAC); v6 and v7 are sortable as text.
 */
internal val UUID_GENERATORS: List<ScratchGenerator> =
    listOf(
        timeGenerator("UUID v1 (time, random node)", "uuid-v1", ::v1),
        ScratchGenerator(
            GeneratorGroup.UUIDS,
            "UUID v4 (random)",
            "uuid-v4",
        ) { context -> lines { randomUuid(context) } },
        timeGenerator("UUID v6 (time, sortable)", "uuid-v6", ::v6),
        ScratchGenerator(GeneratorGroup.UUIDS, "UUID v7 (Unix time, sortable)", "uuid-v7") { context ->
            unixTimeUuids(context)
        },
    )

private fun timeGenerator(
    name: String,
    baseName: String,
    build: (ticks: Long, clockSeq: Long, node: Long) -> UUID,
): ScratchGenerator =
    ScratchGenerator(GeneratorGroup.UUIDS, name, baseName) { context ->
        val now = context.clock.instant()
        var ticks = (now.epochSecond + GREGORIAN_OFFSET_SECONDS) * TICKS_PER_SECOND + now.nano / 100
        val clockSeq = context.random.nextLong() and 0x3fff
        val node = (context.random.nextLong() and NODE_MASK) or MULTICAST_BIT
        lines { build(ticks++, clockSeq, node).toString() }
    }

private fun lsb(
    clockSeq: Long,
    node: Long,
): Long = VARIANT_BITS or (clockSeq shl 48) or node

/** Version 1: time_low, time_mid, version and time_hi, then the clock sequence and node. */
private fun v1(
    ticks: Long,
    clockSeq: Long,
    node: Long,
): UUID {
    val msb =
        ((ticks and 0xffff_ffffL) shl 32) or (((ticks ushr 32) and 0xffff) shl 16) or (1L shl 12) or (ticks ushr 48)
    return UUID(msb, lsb(clockSeq, node))
}

/** Version 6: the same fields with the timestamp most significant first (RFC 9562 section 5.6). */
private fun v6(
    ticks: Long,
    clockSeq: Long,
    node: Long,
): UUID = UUID(((ticks ushr 12) shl 16) or (6L shl 12) or (ticks and 0xfff), lsb(clockSeq, node))

private fun randomUuid(context: GeneratorContext): String {
    val random = context.random
    val msb = (random.nextLong() and (0xfL shl 12).inv()) or (4L shl 12)
    return UUID(msb, (random.nextLong() and (0x3L shl 62).inv()) or VARIANT_BITS).toString()
}

/** Version 7 with a 12-bit counter in rand_a (RFC 9562 section 6.2, method 1), strictly increasing within the file. */
private fun unixTimeUuids(context: GeneratorContext): String {
    val random = context.random
    var millis = context.clock.millis()
    var counter = random.nextInt(COUNTER_LIMIT / 2)
    return lines {
        if (counter >= COUNTER_LIMIT) {
            millis++
            counter = random.nextInt(COUNTER_LIMIT / 2)
        }
        val msb = ((millis and 0xffff_ffff_ffffL) shl 16) or (7L shl 12) or counter.toLong()
        counter++
        UUID(msb, VARIANT_BITS or (random.nextLong() and ((1L shl 62) - 1))).toString()
    }
}
