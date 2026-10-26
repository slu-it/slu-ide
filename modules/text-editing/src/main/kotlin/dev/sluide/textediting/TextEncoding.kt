package dev.sluide.textediting

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * The encodings text files are read and saved in (ADR 0098): UTF-8 first, and Windows-1252, as browsers do, for files
 * that are not valid UTF-8, e.g. old `.properties` or Latin-1 files.
 */
enum class TextEncoding(
    val charset: Charset,
    val label: String,
) {
    UTF_8(Charsets.UTF_8, "UTF-8"),
    WINDOWS_1252(Charset.forName("windows-1252"), "Windows-1252"),
}

/** A file's [text] and the [encoding] it was decoded from. */
data class DecodedText(
    val text: String,
    val encoding: TextEncoding,
)

/**
 * [bytes] decoded: strictly as UTF-8 if they are valid UTF-8, else as Windows-1252 if that gives the same bytes back
 * when encoded (its five undefined bytes `0x81 0x8D 0x8F 0x90 0x9D` don't), else `null`.
 */
fun decodeText(bytes: ByteArray): DecodedText? {
    decodeUtf8(bytes)?.let { return DecodedText(it, TextEncoding.UTF_8) }
    val windows1252 = TextEncoding.WINDOWS_1252.charset
    val text = String(bytes, windows1252)
    val roundTrips = text.toByteArray(windows1252).contentEquals(bytes)
    return if (roundTrips) DecodedText(text, TextEncoding.WINDOWS_1252) else null
}

/** [bytes] as [decodeText] decodes them, or as UTF-8 with replacement characters if it cannot. */
fun decodeTextLeniently(bytes: ByteArray): String = decodeText(bytes)?.text ?: String(bytes, Charsets.UTF_8)

/**
 * Whether [sample], the start of a file (all of it if [complete]), looks like text: it has no NUL byte, and it is
 * valid UTF-8 (a multi-byte character cut off at the end is fine unless the sample is [complete]) or has no C0
 * control byte other than tab, line feed, form feed, carriage return and escape, which a Windows-1252 text would not
 * have, but most binaries do.
 */
fun isTextSample(
    sample: ByteArray,
    complete: Boolean,
): Boolean {
    if (sample.any { it == 0.toByte() }) return false
    val decoder =
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    // UTF-8 never decodes to more chars than bytes, so the output buffer cannot overflow.
    if (!decoder.decode(ByteBuffer.wrap(sample), CharBuffer.allocate(sample.size), complete).isError) return true
    return sample.none { it in 0..0x1F && it.toInt() !in TEXT_CONTROLS }
}

/** Tab, line feed, form feed, carriage return and escape: control bytes that texts have. */
private val TEXT_CONTROLS = setOf(0x09, 0x0A, 0x0C, 0x0D, 0x1B)

/** [bytes] decoded as UTF-8, or `null` if they are not valid UTF-8. */
private fun decodeUtf8(bytes: ByteArray): String? =
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
