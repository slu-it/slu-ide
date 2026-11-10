package dev.sluide.encryptedfiles

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/** Derives the 32-byte key that wraps a file's DEK; the caller zeroes the result. */
internal fun interface Kdf {
    fun derive(
        password: CharArray,
        salt: ByteArray,
        params: KdfParams,
    ): ByteArray
}

/**
 * Argon2id (version 0x13) through BouncyCastle's lightweight API, with the password as UTF-8 of its NFC form
 * (ADR 0162), so a password typed with composed or decomposed accents derives the same key. The UTF-8 copy of the
 * password is zeroed after use; the caller's array is left as it is.
 */
internal object Argon2id : Kdf {
    private val NFC = Normalizer.Form.NFC

    /** First code unit that may compose or decompose: combining marks start here, Hangul jamo at U+1100. */
    internal const val FIRST_NON_NFC_SAFE = '\u0300'

    /** Text of characters below [FIRST_NON_NFC_SAFE] only is NFC already: none of them decomposes or composes. */
    internal fun isNfcWithoutString(password: CharArray): Boolean = password.all { it < FIRST_NON_NFC_SAFE }

    override fun derive(
        password: CharArray,
        salt: ByteArray,
        params: KdfParams,
    ): ByteArray {
        val parameters =
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(params.memoryKiB)
                .withIterations(params.passes)
                .withParallelism(params.parallelism)
                .build()
        val encoded = utf8(password)
        try {
            val generator = Argon2BytesGenerator()
            generator.init(parameters)
            val out = ByteArray(SluencFormat.KEY_BYTES)
            generator.generateBytes(encoded, out)
            return out
        } finally {
            encoded.fill(0)
            parameters.clear()
        }
    }

    /**
     * UTF-8 of [password] in NFC; an unpaired surrogate becomes `?`. The encoder's buffer is zeroed as well. A password
     * with a character from U+0300 upward passes through a String during normalization, which cannot be zeroed (a
     * documented limit; `Normalizer` makes a String of every input, `isNormalized` too).
     */
    private fun utf8(password: CharArray): ByteArray {
        val chars = CharBuffer.wrap(password)
        val normalized: CharSequence = if (isNfcWithoutString(password)) chars else Normalizer.normalize(chars, NFC)
        val encoder =
            Charsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val buffer = ByteBuffer.allocate((normalized.length * encoder.maxBytesPerChar()).toInt())
        try {
            encoder.encode(CharBuffer.wrap(normalized), buffer, true)
            encoder.flush(buffer)
            return buffer.array().copyOf(buffer.position())
        } finally {
            buffer.array().fill(0)
        }
    }
}
