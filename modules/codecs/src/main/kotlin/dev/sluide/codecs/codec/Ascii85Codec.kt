package dev.sluide.codecs.codec

import java.io.ByteArrayOutputStream

enum class Ascii85Variant(
    val title: String,
) {
    ADOBE("Adobe"),
    Z85("Z85"),
}

/** Ascii85: the Adobe form with `<~ ~>` and `z`, or Z85 (ZeroMQ RFC 32). */
internal class Ascii85Codec(
    private val variant: Ascii85Variant = Ascii85Variant.ADOBE,
) : ByteCodec {
    override fun encode(bytes: ByteArray): Decoded<String> =
        when (variant) {
            Ascii85Variant.ADOBE -> {
                Decoded.Value(encodeAdobe(bytes))
            }

            Ascii85Variant.Z85 -> {
                if (bytes.size % 4 != 0) {
                    Decoded.Error("Z85 needs a multiple of 4 bytes")
                } else {
                    Decoded.Value(encodeZ85(bytes))
                }
            }
        }

    override fun decode(text: String): Decoded<ByteArray> =
        when (variant) {
            Ascii85Variant.ADOBE -> decodeAdobe(text)
            Ascii85Variant.Z85 -> decodeZ85(text)
        }

    private fun groupValue(
        bytes: ByteArray,
        from: Int,
        count: Int,
    ): Long {
        var v = 0L
        for (k in 0 until 4) v = (v shl 8) or (if (k < count) (bytes[from + k].toLong() and 0xFF) else 0L)
        return v
    }

    private fun digits(value: Long): IntArray {
        val d = IntArray(5)
        var v = value
        for (k in 4 downTo 0) {
            d[k] = (v % 85).toInt()
            v /= 85
        }
        return d
    }

    private fun encodeZ85(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (from in bytes.indices step 4) {
            digits(groupValue(bytes, from, 4)).forEach { sb.append(Z85_ALPHABET[it]) }
        }
        return sb.toString()
    }

    private fun encodeAdobe(bytes: ByteArray): String {
        val sb = StringBuilder("<~")
        for (from in bytes.indices step 4) {
            val count = minOf(4, bytes.size - from)
            val value = groupValue(bytes, from, count)
            if (count == 4 && value == 0L) {
                sb.append('z')
            } else {
                val d = digits(value)
                for (k in 0..count) sb.append((d[k] + '!'.code).toChar())
            }
        }
        return sb.append("~>").toString()
    }

    private fun decodeAdobe(text: String): Decoded<ByteArray> {
        var start = 0
        var end = text.length
        val firstIdx = text.indexOfFirst { !it.isWhitespace() }
        if (firstIdx >= 0 && text.startsWith("<~", firstIdx)) start = firstIdx + 2
        val lastIdx = text.indexOfLast { !it.isWhitespace() }
        if (lastIdx >= start + 1 && text.startsWith("~>", lastIdx - 1)) end = lastIdx - 1
        val out = ByteArrayOutputStream()
        val group = IntArray(5)
        var count = 0
        var lastIndex = -1
        for (i in start until end) {
            val c = text[i]
            if (c.isWhitespace()) continue
            if (c == 'z') {
                if (count != 0) return Decoded.Error("'z' inside an Ascii85 group", i)
                repeat(4) { out.write(0) }
                continue
            }
            if (c !in '!'..'u') return Decoded.Error("Invalid Ascii85 character '$c'", i)
            group[count++] = c - '!'
            lastIndex = i
            if (count == 5) {
                val value = group.fold(0L) { acc, d -> acc * 85 + d }
                if (value > MAX_GROUP) return Decoded.Error("Ascii85 group is too large", i)
                writeGroup(out, value, 4)
                count = 0
            }
        }
        if (count == 1) return Decoded.Error("Ascii85 ends in the middle of a group", lastIndex)
        if (count > 1) {
            for (k in count until 5) group[k] = 84
            val value = group.fold(0L) { acc, d -> acc * 85 + d }
            if (value > MAX_GROUP) return Decoded.Error("Ascii85 group is too large", lastIndex)
            writeGroup(out, value, count - 1)
        }
        return Decoded.Value(out.toByteArray())
    }

    private fun decodeZ85(text: String): Decoded<ByteArray> {
        val out = ByteArrayOutputStream()
        var count = 0
        var value = 0L
        var lastIndex = -1
        for ((i, c) in text.withIndex()) {
            if (c.isWhitespace()) continue
            val d = Z85_ALPHABET.indexOf(c)
            if (d < 0) return Decoded.Error("Invalid Z85 character '$c'", i)
            value = value * 85 + d
            lastIndex = i
            if (++count == 5) {
                if (value > MAX_GROUP) return Decoded.Error("Z85 group is too large", i)
                writeGroup(out, value, 4)
                count = 0
                value = 0
            }
        }
        if (count != 0) return Decoded.Error("Z85 needs a multiple of 5 characters", lastIndex)
        return Decoded.Value(out.toByteArray())
    }

    private fun writeGroup(
        out: ByteArrayOutputStream,
        value: Long,
        bytes: Int,
    ) {
        for (k in 0 until bytes) out.write(((value shr (24 - 8 * k)) and 0xFF).toInt())
    }

    private companion object {
        const val MAX_GROUP = 0xFFFFFFFFL
        const val Z85_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-:+=^!/*?&<>()[]{}@%$#"
    }
}
