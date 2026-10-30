package dev.sluide.codecs.codec

import java.math.BigInteger

/** Base58 with the Bitcoin alphabet; leading zero bytes become leading `1`s. */
internal class Base58Codec : ByteCodec {
    override fun encode(bytes: ByteArray): Decoded<String> {
        if (bytes.size > MAX_BYTES) return Decoded.Error("Base58 is limited to 1 024 bytes")
        val zeros = bytes.takeWhile { it == 0.toByte() }.size
        val sb = StringBuilder()
        var n = BigInteger(1, bytes)
        while (n.signum() > 0) {
            val qr = n.divideAndRemainder(BASE)
            sb.append(ALPHABET[qr[1].toInt()])
            n = qr[0]
        }
        repeat(zeros) { sb.append(ALPHABET[0]) }
        return Decoded.Value(sb.reverse().toString())
    }

    override fun decode(text: String): Decoded<ByteArray> {
        if (text.count { !it.isWhitespace() } > MAX_CHARACTERS) {
            return Decoded.Error("Base58 is limited to 1 400 characters")
        }
        var n = BigInteger.ZERO
        var zeros = 0
        var leading = true
        for ((i, c) in text.withIndex()) {
            if (c.isWhitespace()) continue
            val v = ALPHABET.indexOf(c)
            if (v < 0) return Decoded.Error("Invalid Base58 character '$c'", i)
            if (leading && v == 0) zeros++ else leading = false
            n = n.multiply(BASE).add(BigInteger.valueOf(v.toLong()))
        }
        val body = if (n.signum() == 0) ByteArray(0) else n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return Decoded.Value(ByteArray(zeros) + body)
    }

    private companion object {
        // The conversion is quadratic and runs on the FX thread per keystroke; Base58 is meant for short IDs and keys.
        const val MAX_BYTES = 1024
        const val MAX_CHARACTERS = 1400
        const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val BASE: BigInteger = BigInteger.valueOf(58)
    }
}
