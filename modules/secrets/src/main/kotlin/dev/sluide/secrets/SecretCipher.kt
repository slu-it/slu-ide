package dev.sluide.secrets

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets.UTF_8

/**
 * Encrypts saved secrets with AES-256-GCM (ADR 0122). A value is `v1:` + base64(nonce ‖ ciphertext ‖ tag) with a fresh
 * 12-byte nonce from [random] and a 128-bit tag; the caller's context (e.g. `databases/<id>/password`) is the
 * associated data, so a value only decrypts where it was written. Plain text never passes through a `String`, and
 * every intermediate buffer holding it is zeroed.
 */
internal class SecretCipher(
    private val random: SecureRandom,
) {
    fun encrypt(
        key: ByteArray,
        plain: CharArray,
        context: String,
    ): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val encoded = utf8(plain)
        try {
            val cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, context)
            val sealed = cipher.doFinal(encoded.array(), 0, encoded.position())
            return PREFIX + Base64.getEncoder().encodeToString(nonce + sealed)
        } finally {
            encoded.array().fill(0)
        }
    }

    /** The plain text, or `null` when [value] is unreadable: unknown prefix, bad base64, too short or a failed tag. */
    fun decrypt(
        key: ByteArray,
        value: String,
        context: String,
    ): CharArray? {
        val bytes = payload(value) ?: return null
        val plain =
            try {
                cipher(Cipher.DECRYPT_MODE, key, bytes.copyOfRange(0, NONCE_BYTES), context)
                    .doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES)
            } catch (_: GeneralSecurityException) {
                return null
            }
        try {
            return chars(plain)
        } finally {
            plain.fill(0)
        }
    }

    private fun cipher(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        context: String,
    ): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(context.toByteArray(UTF_8))
        }

    /** UTF-8 of [plain] in a buffer sized for the worst case, so the encoder never reallocates (and leaves copies). */
    private fun utf8(plain: CharArray): ByteBuffer {
        val encoder =
            UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val out = ByteBuffer.allocate((plain.size * encoder.maxBytesPerChar()).toInt())
        encoder.encode(CharBuffer.wrap(plain), out, true)
        encoder.flush(out)
        return out
    }

    private fun chars(plain: ByteArray): CharArray {
        val decoder =
            UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val out = CharBuffer.allocate((plain.size * decoder.maxCharsPerByte()).toInt())
        try {
            decoder.decode(ByteBuffer.wrap(plain), out, true)
            decoder.flush(out)
            return out.array().copyOf(out.position())
        } finally {
            out.array().fill('\u0000')
        }
    }

    companion object {
        private const val PREFIX = "v1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val MIN_BYTES = NONCE_BYTES + TAG_BITS / 8

        /** Whether [value] has the form of a saved secret; one that does not can never be decrypted. */
        fun isWellFormed(value: String): Boolean = payload(value) != null

        /** nonce ‖ ciphertext ‖ tag, or `null` for an unknown prefix, bad base64 or too few bytes. */
        private fun payload(value: String): ByteArray? {
            if (!value.startsWith(PREFIX)) return null
            val bytes =
                try {
                    Base64.getDecoder().decode(value.substring(PREFIX.length))
                } catch (_: IllegalArgumentException) {
                    return null
                }
            return bytes.takeIf { it.size >= MIN_BYTES }
        }
    }
}
