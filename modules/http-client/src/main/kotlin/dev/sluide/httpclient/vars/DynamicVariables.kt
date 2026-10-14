package dev.sluide.httpclient.vars

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.random.RandomGenerator

/**
 * The `$` variables computed on each use: `$uuid`, `$random.uuid`, `$timestamp` (epoch seconds), `$isoTimestamp`
 * (UTC), `$randomInt` (0 to 999), `$random.integer(…)` and `$random.float(…)` (`(from, to)`, `(to)` or `()`, from
 * inclusive, to exclusive, 0 to 1000 by default), `$random.alphabetic(n)`, `$random.alphanumeric(n)` (with `_`),
 * `$random.hexadecimal(n)` (n up to 10 000), `$random.email` and `$env.NAME` (the operating system environment).
 */
internal class DynamicVariables(
    private val clock: Clock = Clock.systemUTC(),
    private val random: RandomGenerator = SecureRandom(),
    private val env: (String) -> String? = System::getenv,
) {
    /**
     * The value of [name] called with [args] (empty without parentheses), or null if unknown or invalid; throws
     * [IllegalArgumentException] for random strings longer than 10 000 characters.
     */
    fun value(
        name: String,
        args: List<String> = emptyList(),
    ): String? {
        if (name.startsWith(ENV_PREFIX) && args.isEmpty()) return env(name.removePrefix(ENV_PREFIX))
        return when (name) {
            "\$uuid", "\$random.uuid" -> {
                noArgs(args) { uuid() }
            }

            "\$timestamp" -> {
                noArgs(args) { clock.instant().epochSecond.toString() }
            }

            "\$isoTimestamp" -> {
                noArgs(args) {
                    DateTimeFormatter.ISO_INSTANT.format(clock.instant().truncatedTo(ChronoUnit.MILLIS))
                }
            }

            "\$randomInt" -> {
                noArgs(args) { random.nextInt(0, DEFAULT_BOUND).toString() }
            }

            "\$random.integer" -> {
                integer(args)
            }

            "\$random.float" -> {
                float(args)
            }

            "\$random.alphabetic" -> {
                characters(args, LETTERS)
            }

            "\$random.alphanumeric" -> {
                characters(args, "$LETTERS${DIGITS}_")
            }

            "\$random.hexadecimal" -> {
                characters(args, "${DIGITS}abcdef")
            }

            "\$random.email" -> {
                noArgs(args) { "${string(8, LOWER)}@${string(6, LOWER)}.com" }
            }

            else -> {
                null
            }
        }
    }

    private fun noArgs(
        args: List<String>,
        value: () -> String,
    ) = if (args.isEmpty()) value() else null

    private fun integer(args: List<String>): String? {
        val numbers = args.map { it.toLongOrNull() ?: return null }
        val (from, to) =
            when (numbers.size) {
                0 -> 0L to DEFAULT_BOUND.toLong()
                1 -> 0L to numbers[0]
                2 -> numbers[0] to numbers[1]
                else -> return null
            }
        return if (from < to) random.nextLong(from, to).toString() else null
    }

    private fun float(args: List<String>): String? {
        val numbers = args.map { it.toDoubleOrNull() ?: return null }
        val (from, to) =
            when (numbers.size) {
                0 -> 0.0 to DEFAULT_BOUND.toDouble()
                1 -> 0.0 to numbers[0]
                2 -> numbers[0] to numbers[1]
                else -> return null
            }
        return if (from < to) random.nextDouble(from, to).toString() else null
    }

    private fun characters(
        args: List<String>,
        alphabet: String,
    ): String? {
        val length = args.singleOrNull()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        require(length <= MAX_LENGTH) { "at most $MAX_LENGTH characters" }
        return string(length, alphabet)
    }

    private fun string(
        length: Int,
        alphabet: String,
    ) = String(CharArray(length) { alphabet[random.nextInt(alphabet.length)] })

    private fun uuid(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        bytes[6] = (bytes[6].toInt() and 0x0f or 0x40).toByte()
        bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.getLong(), buffer.getLong()).toString()
    }

    private companion object {
        const val ENV_PREFIX = "\$env."
        const val DEFAULT_BOUND = 1000
        const val MAX_LENGTH = 10_000
        const val LOWER = "abcdefghijklmnopqrstuvwxyz"
        const val LETTERS = "${LOWER}ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        const val DIGITS = "0123456789"
    }
}
