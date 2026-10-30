package dev.sluide.codecs.codec

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** How [UrlCodec] treats spaces and `+`. */
enum class UrlMode(
    val title: String,
) {
    COMPONENT("Component"),
    FORM("Form"),
}

/** Percent-encoding: RFC 3986 components, or application/x-www-form-urlencoded. */
internal class UrlCodec(
    private val mode: UrlMode = UrlMode.COMPONENT,
    private val charset: Charset = Charsets.UTF_8,
) : TextCodec {
    override fun encode(text: String): Decoded<String> {
        // UTF-16 would write a byte order mark per character; percent-encoding uses big-endian without one.
        val encoding = if (charset == Charsets.UTF_16) Charsets.UTF_16BE else charset
        val encoder = encoding.newEncoder()
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val len = Character.charCount(text.codePointAt(i))
            val c = text[i]
            when {
                len == 1 && isKept(c) -> {
                    out.append(c)
                }

                len == 1 && c == ' ' && mode == UrlMode.FORM -> {
                    out.append('+')
                }

                else -> {
                    val part = text.substring(i, i + len)
                    if (!encoder.canEncode(part)) {
                        return Decoded.Error("'$part' cannot be written in ${charset.name()}", i)
                    }
                    for (b in part.toByteArray(encoding)) out.append('%').append("%02X".format(b.toInt() and 0xFF))
                }
            }
            i += len
        }
        return Decoded.Value(out.toString())
    }

    private fun isKept(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' ||
            if (mode == UrlMode.FORM) c == '*' else c == '~'

    override fun decode(text: String): Decoded<String> {
        val out = StringBuilder()
        val run = ByteArrayOutputStream()
        var runStart = -1
        var i = 0

        fun flush(): Decoded.Error? {
            if (runStart < 0) return null
            val decoder =
                charset
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            val error =
                try {
                    out.append(decoder.decode(ByteBuffer.wrap(run.toByteArray())))
                    null
                } catch (_: CharacterCodingException) {
                    Decoded.Error("Bytes are not valid ${charset.name()}", runStart)
                }
            run.reset()
            runStart = -1
            return error
        }
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                val hi = if (i + 1 < text.length) hexValue(text[i + 1]) else -1
                val lo = if (i + 2 < text.length) hexValue(text[i + 2]) else -1
                if (hi < 0 || lo < 0) return Decoded.Error("Invalid percent escape", i)
                if (runStart < 0) runStart = i
                run.write(hi * 16 + lo)
                i += 3
            } else {
                flush()?.let { return it }
                out.append(if (c == '+' && mode == UrlMode.FORM) ' ' else c)
                i++
            }
        }
        flush()?.let { return it }
        return Decoded.Value(out.toString())
    }
}
