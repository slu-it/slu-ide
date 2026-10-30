package dev.sluide.scratches.generators

import dev.sluide.scratches.GENERATED_VALUES
import dev.sluide.scratches.GeneratorContext
import dev.sluide.scratches.GeneratorGroup
import dev.sluide.scratches.ScratchGenerator
import dev.sluide.scratches.lines
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.HexFormat

private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private const val NANO_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
private const val KSUID_EPOCH = 1_400_000_000L
private const val TWITTER_EPOCH = 1_288_834_974_657L
private const val ULID_RANDOM_BITS = 80
private const val OBJECT_ID_COUNTER = 1 shl 24
private const val SEQUENCE_LIMIT = 1 shl 12
private val FIVE_BITS: BigInteger = BigInteger.valueOf(31)

/**
 * The Id entries of the generated scratch file picker (ADR 0114): 50 ids per file. ULID, MongoDB ObjectId and
 * Snowflake ID are strictly increasing within the file (one clock read, then a counter); KSUID and NanoID are not.
 */
internal val ID_GENERATORS: List<ScratchGenerator> =
    listOf(
        ScratchGenerator(GeneratorGroup.IDS, "ULID", "ulid", generate = ::ulids),
        ScratchGenerator(GeneratorGroup.IDS, "NanoID", "nanoid") { context ->
            lines { String(CharArray(21) { NANO_ALPHABET[context.random.nextInt(NANO_ALPHABET.length)] }) }
        },
        ScratchGenerator(GeneratorGroup.IDS, "KSUID", "ksuid", generate = ::ksuids),
        ScratchGenerator(GeneratorGroup.IDS, "MongoDB ObjectId", "objectid", generate = ::objectIds),
        ScratchGenerator(GeneratorGroup.IDS, "Snowflake ID", "snowflake", generate = ::snowflakes),
    )

private fun GeneratorContext.bytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

/** 48-bit milliseconds and 80 random bits, the random part plus one for each following id of the file. */
private fun ulids(context: GeneratorContext): String {
    var millis = context.clock.millis()
    var random = BigInteger(1, context.bytes(ULID_RANDOM_BITS / 8))
    return lines {
        if (random.bitLength() > ULID_RANDOM_BITS) {
            millis++
            random = BigInteger(1, context.bytes(ULID_RANDOM_BITS / 8))
        }
        var value = BigInteger.valueOf(millis).shiftLeft(ULID_RANDOM_BITS).or(random)
        random = random.add(BigInteger.ONE)
        String(
            CharArray(
                26,
            ) { CROCKFORD[value.and(FIVE_BITS).toInt()].also { value = value.shiftRight(5) } }.reversedArray(),
        )
    }
}

private fun base62(value: BigInteger): String {
    val base = BigInteger.valueOf(62)
    var rest = value
    val chars = CharArray(27) { '0' }
    for (i in chars.indices.reversed()) {
        val (quotient, remainder) = rest.divideAndRemainder(base)
        chars[i] = BASE62[remainder.toInt()]
        rest = quotient
    }
    return String(chars)
}

/** 4-byte seconds since 2014-05-13 and 16 random bytes in Base62. */
private fun ksuids(context: GeneratorContext): String {
    val seconds = context.clock.instant().epochSecond - KSUID_EPOCH
    return lines {
        val bytes =
            ByteBuffer
                .allocate(20)
                .putInt(seconds.toInt())
                .put(context.bytes(16))
                .array()
        base62(BigInteger(1, bytes))
    }
}

/** 4-byte Unix seconds, 5 random bytes and a 3-byte counter from a random start that cannot wrap in the file. */
private fun objectIds(context: GeneratorContext): String {
    val seconds =
        context.clock
            .instant()
            .epochSecond
            .toInt()
    val machine = context.bytes(5)
    var counter = context.random.nextInt(OBJECT_ID_COUNTER - GENERATED_VALUES)
    return lines {
        val bytes =
            ByteBuffer
                .allocate(12)
                .putInt(seconds)
                .put(machine)
                .put((counter shr 16).toByte())
                .put((counter shr 8).toByte())
                .put(counter.toByte())
                .array()
        counter++
        HexFormat.of().formatHex(bytes)
    }
}

/** 41-bit milliseconds since the Twitter epoch, a random 10-bit machine id and a 12-bit sequence. */
private fun snowflakes(context: GeneratorContext): String {
    var millis = context.clock.millis() - TWITTER_EPOCH
    val machine = context.random.nextInt(1 shl 10).toLong()
    var sequence = 0
    return lines {
        if (sequence >= SEQUENCE_LIMIT) {
            millis++
            sequence = 0
        }
        val id = (millis shl 22) or (machine shl 12) or sequence.toLong()
        sequence++
        id.toString()
    }
}
