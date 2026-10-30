package dev.sluide.scratches.generators

import dev.sluide.scratches.GeneratorGroup
import dev.sluide.scratches.ScratchGenerator
import dev.sluide.scratches.lines
import java.util.Base64
import java.util.HexFormat
import java.util.random.RandomGenerator

private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
private const val DIGITS = "0123456789"
private const val SYMBOLS = "!#$%&*+-.:;=?@^_~"

private val PASSWORD_LENGTHS = listOf(12, 16, 20, 32, 64)
private val BYTE_SIZES = listOf(16, 24, 32, 64)

private fun String.pick(random: RandomGenerator): Char = this[random.nextInt(length)]

/**
 * A password of [length] characters from the union of the [classes], with at least one character of each class:
 * one drawn per class, the rest from the whole alphabet, then all shuffled (Fisher-Yates) so positions are unbiased.
 */
private fun password(
    random: RandomGenerator,
    length: Int,
    classes: List<String>,
): String {
    require(length >= classes.size) { "length $length is below the ${classes.size} required classes" }
    val alphabet = classes.joinToString("")
    val chars = CharArray(length) { if (it < classes.size) classes[it].pick(random) else alphabet.pick(random) }
    for (i in chars.indices.reversed()) {
        val j = random.nextInt(i + 1)
        val swap = chars[i]
        chars[i] = chars[j]
        chars[j] = swap
    }
    return String(chars)
}

private fun passwordGenerator(
    name: String,
    baseName: String,
    length: Int,
    classes: List<String>,
) = ScratchGenerator(GeneratorGroup.SECRETS, name, baseName) { context ->
    lines { password(context.random, length, classes) }
}

private fun bytesGenerator(
    name: String,
    baseName: String,
    size: Int,
    encode: (ByteArray) -> String,
) = ScratchGenerator(GeneratorGroup.SECRETS, name, baseName) { context ->
    lines { encode(ByteArray(size).also(context.random::nextBytes)) }
}

/**
 * The Secrets entries of the generated scratch file picker (ADR 0114): 50 random passwords of 12 to 64 characters with
 * letters, digits and symbols, with letters and digits, or with digits only, then 50 random byte values of 16 to 64
 * bytes as hex, Base64 and Base64URL. All randomness comes from the context's random source.
 */
internal val SECRET_GENERATORS: List<ScratchGenerator> =
    PASSWORD_LENGTHS.flatMap { n ->
        listOf(
            passwordGenerator(
                "Password $n (letters, digits, symbols)",
                "password-$n",
                n,
                listOf(UPPER, LOWER, DIGITS, SYMBOLS),
            ),
            passwordGenerator(
                "Password $n (letters, digits)",
                "password-$n-alphanumeric",
                n,
                listOf(UPPER, LOWER, DIGITS),
            ),
            passwordGenerator("Password $n (digits)", "password-$n-digits", n, listOf(DIGITS)),
        )
    } +
        BYTE_SIZES.flatMap { n ->
            listOf(
                bytesGenerator("Random $n bytes (hex)", "bytes-$n-hex", n) { HexFormat.of().formatHex(it) },
                bytesGenerator(
                    "Random $n bytes (Base64)",
                    "bytes-$n-base64",
                    n,
                ) { Base64.getEncoder().encodeToString(it) },
                bytesGenerator("Random $n bytes (Base64URL)", "bytes-$n-base64url", n) {
                    Base64.getUrlEncoder().withoutPadding().encodeToString(it)
                },
            )
        }
