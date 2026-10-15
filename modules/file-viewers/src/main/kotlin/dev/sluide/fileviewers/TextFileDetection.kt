package dev.sluide.fileviewers

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/** Number of leading bytes inspected to decide whether a file is text. */
internal const val TEXT_SAMPLE_SIZE = 8 * 1024

/** Larger files are not shown, a text control holding them would block the UI. */
internal const val MAX_TEXT_FILE_SIZE = 10L * 1024 * 1024

/**
 * Whether [file] is a readable regular text file of at most [MAX_TEXT_FILE_SIZE] bytes.
 *
 * Its first [TEXT_SAMPLE_SIZE] bytes must not contain a NUL byte and must be valid UTF-8; a multi-byte
 * character cut off at the end of the sample is fine. Empty files are text. IO errors yield `false`.
 */
fun isTextFile(file: Path): Boolean = readTextSample(file) != null

/**
 * The first [TEXT_SAMPLE_SIZE] bytes of [file] if it is a text file as [isTextFile] decides, else `null`, so callers
 * that also look at the start of the file read it only once.
 */
internal fun readTextSample(file: Path): ByteArray? =
    try {
        if (Files.isRegularFile(file) && Files.size(file) <= MAX_TEXT_FILE_SIZE) {
            readSample(file).takeIf(::isTextSample)
        } else {
            null
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

/**
 * The first line of a text [sample] without its line break (`\n` or `\r\n`); cut off at the end of the sample if
 * the line is longer.
 */
internal fun firstLine(sample: ByteArray): String {
    // 0x0A never occurs inside a multi-byte UTF-8 character.
    val end = sample.indexOf('\n'.code.toByte()).takeIf { it >= 0 } ?: sample.size
    return String(sample, 0, end, Charsets.UTF_8).removeSuffix("\r")
}

private fun readSample(file: Path): ByteArray =
    Files.newInputStream(file).use { input -> input.readNBytes(TEXT_SAMPLE_SIZE) }

private fun isTextSample(sample: ByteArray): Boolean {
    if (sample.any { it == 0.toByte() }) return false
    val decoder =
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    // A full sample may end inside a multi-byte character; only a shorter sample is the real end of the file.
    val endOfInput = sample.size < TEXT_SAMPLE_SIZE
    // UTF-8 never decodes to more chars than bytes, so the output buffer cannot overflow.
    return !decoder.decode(ByteBuffer.wrap(sample), CharBuffer.allocate(sample.size), endOfInput).isError
}

/** [bytes] decoded as UTF-8, or `null` if they are not valid UTF-8. */
internal fun decodeUtf8(bytes: ByteArray): String? =
    try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
