package dev.sluide.codecs.detect

import dev.sluide.codecs.codec.Base32Codec
import dev.sluide.codecs.codec.Base64Codec
import dev.sluide.codecs.codec.BytePipeline
import dev.sluide.codecs.codec.CodecId
import dev.sluide.codecs.codec.DataUri
import dev.sluide.codecs.codec.Decoded
import dev.sluide.codecs.codec.HtmlEntities
import dev.sluide.codecs.codec.Jwt
import dev.sluide.codecs.codec.UrlCodec

/** A guess of the codec that made a text; [description] completes "Looks like ...". */
data class Detection(
    val codec: CodecId,
    val description: String,
    val strong: Boolean,
)

/** Guesses which codec produced a text. Only strong guesses are certain enough to switch without asking. */
internal object CodecDetector {
    private const val MIN_LENGTH = 8
    private const val BASE32_BLOCK = 8
    private const val MIN_BASE32_BYTES = 4
    private val jwtShape =
        Regex("^(?:bearer\\s+)?[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*$", RegexOption.IGNORE_CASE)
    private val hexSeparators = Regex("[\\s:-]+")
    private val hexByte = Regex("[0-9a-fA-F]{2}")
    private val hexRun = Regex("[0-9a-fA-F]+")
    private val base32Shape = Regex("[A-Z2-7]+=*")
    private val base64Shape = Regex("[A-Za-z0-9+/]+={0,2}")
    private val base64UrlShape = Regex("[A-Za-z0-9_-]+={0,2}")
    private val percent = Regex("%[0-9A-Fa-f]{2}")
    private val reference = Regex("&(?:#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);")
    private val unicode = Regex("\\\\u(?:[0-9a-fA-F]{4}|\\{[0-9a-fA-F]+})")
    private val jsonEscape = Regex("\\\\[nt\"\\\\]")

    fun detect(text: String): Detection? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return jwt(t)
            ?: dataUri(t)
            ?: ascii85(t)
            ?: hex(t)
            ?: base32(t)
            ?: base64(t)
            ?: url(t)
            ?: html(t)
            ?: escapes(t)
    }

    private fun jwt(t: String): Detection? =
        if (jwtShape.matches(t) && Jwt.decode(t) is Decoded.Value) Detection(CodecId.JWT, "a JWT", true) else null

    private fun dataUri(t: String): Detection? =
        if (DataUri.parse(t)?.base64 == true) Detection(CodecId.BASE64, "a Base64 data URI", true) else null

    private fun ascii85(t: String): Detection? =
        if (t.length >= 4 && t.startsWith("<~") && t.endsWith("~>")) {
            Detection(CodecId.ASCII85, "Ascii85", true)
        } else {
            null
        }

    private fun hex(t: String): Detection? {
        val groups = t.split(hexSeparators).map { it.removePrefix("0x").removePrefix("0X") }
        val digits = groups.joinToString("")
        if (digits.length < MIN_LENGTH || digits.length % 2 != 0) return null
        if (groups.any { !hexRun.matches(it) || it.length % 2 != 0 }) return null
        val letters = digits.any { it in 'a'..'f' || it in 'A'..'F' }
        if (groups.size == 1) {
            val prefixed = t.contains("0x") || t.contains("0X")
            return if (letters || prefixed) Detection(CodecId.HEX, "hex", false) else null
        }
        // Separated input: single bytes (`48 65 6c`) or any grouping with a letter (xxd's `4865 6c6c`); this keeps
        // dates, phone and card numbers (`2026-10-30`, `4111 1111 1111 1111`) out.
        val bytes = groups.all { hexByte.matches(it) }
        return if (bytes || letters) Detection(CodecId.HEX, "hex", false) else null
    }

    private fun base32(t: String): Detection? {
        if (t.length < MIN_LENGTH || t.length % BASE32_BLOCK != 0 || !base32Shape.matches(t)) return null
        // Plain upper-case words ("PASSWORD") are valid Base32 letters; a digit or padding says it is meant.
        if (t.none { it in '2'..'7' || it == '=' }) return null
        val bytes = (Base32Codec().decode(t) as? Decoded.Value)?.value ?: return null
        val readable = bytes.size >= MIN_BASE32_BYTES && isPlainText(bytes)
        return if (readable) Detection(CodecId.BASE32, "Base32", false) else null
    }

    private fun base64(t: String): Detection? {
        val compact = t.filter { !it.isWhitespace() }
        if (compact.length < MIN_LENGTH) return null
        val url = base64UrlShape.matches(compact) && compact.any { it == '-' || it == '_' }
        if (!url && !base64Shape.matches(compact)) return null
        val unpadded = compact.trimEnd('=')
        if (unpadded.length % 4 == 1 || (compact.length != unpadded.length && compact.length % 4 != 0)) return null
        val bytes = (Base64Codec().decode(compact) as? Decoded.Value)?.value ?: return null
        if (!isPlainText(bytes) && FileKind.of(bytes) == null) return null
        return Detection(CodecId.BASE64, if (url) "Base64URL" else "Base64", false)
    }

    private fun url(t: String): Detection? =
        if (percent.containsMatchIn(t) && UrlCodec().decode(t) is Decoded.Value) {
            Detection(CodecId.URL, "URL-encoded text", false)
        } else {
            null
        }

    private fun html(t: String): Detection? =
        if (reference.containsMatchIn(t) && (HtmlEntities().decode(t) as? Decoded.Value)?.value != t) {
            Detection(CodecId.HTML, "HTML entities", false)
        } else {
            null
        }

    private fun escapes(t: String): Detection? =
        when {
            unicode.containsMatchIn(t) -> Detection(CodecId.UNICODE, "Unicode escapes", false)
            jsonEscape.containsMatchIn(t) -> Detection(CodecId.JSON, "a JSON string", false)
            else -> null
        }

    /** UTF-8 text without control characters other than tab, CR and LF. */
    private fun isPlainText(bytes: ByteArray): Boolean {
        val s = BytePipeline.decodeText(bytes, Charsets.UTF_8) ?: return false
        return s.none { (it < ' ' && it != '\t' && it != '\r' && it != '\n') || it == '\u007f' }
    }
}
