package dev.sluide.secrets

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.security.SecureRandom
import java.util.Base64

class SecretCipherTest {
    private val cipher = SecretCipher(SecureRandom())
    private val key = ByteArray(32) { it.toByte() }
    private val context = "databases/42/password"

    @ParameterizedTest
    @ValueSource(strings = ["", "secret", "pässwörd ✓ 秘密 🔑"])
    fun `round trip`(plain: String) {
        val value = cipher.encrypt(key, plain.toCharArray(), context)

        assertTrue(value.startsWith("v1:"), value)
        assertTrue(SecretCipher.isWellFormed(value))
        assertArrayEquals(plain.toCharArray(), cipher.decrypt(key, value, context))
    }

    @Test
    fun `nonce ‖ ciphertext ‖ tag in standard base64`() {
        val bytes = Base64.getDecoder().decode(cipher.encrypt(key, "abc".toCharArray(), context).removePrefix("v1:"))

        assertEquals(12 + 3 + 16, bytes.size)
    }

    @Test
    fun `every value gets its own nonce`() {
        val first = cipher.encrypt(key, "same".toCharArray(), context)
        val second = cipher.encrypt(key, "same".toCharArray(), context)

        assertNotEquals(first, second)
        assertNotEquals(first.substring(3, 19), second.substring(3, 19), "the first 12 bytes differ")
    }

    @Test
    fun `wrong context is unreadable`() {
        val value = cipher.encrypt(key, "secret".toCharArray(), context)

        assertNull(cipher.decrypt(key, value, "databases/43/password"))
    }

    @Test
    fun `wrong key is unreadable`() {
        val value = cipher.encrypt(key, "secret".toCharArray(), context)

        assertNull(cipher.decrypt(ByteArray(32), value, context))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 14, 30])
    fun `tampered byte is unreadable`(index: Int) {
        val value = cipher.encrypt(key, "secret".toCharArray(), context)
        val bytes = Base64.getDecoder().decode(value.removePrefix("v1:"))
        bytes[index] = (bytes[index].toInt() xor 1).toByte()

        assertNull(cipher.decrypt(key, "v1:" + Base64.getEncoder().encodeToString(bytes), context))
    }

    @ParameterizedTest
    @MethodSource("malformed")
    fun `malformed values are unreadable`(value: String) {
        assertFalse(SecretCipher.isWellFormed(value))
        assertNull(cipher.decrypt(key, value, context))
    }

    companion object {
        private fun base64(size: Int) = Base64.getEncoder().encodeToString(ByteArray(size) { 7 })

        @JvmStatic
        fun malformed() =
            listOf(
                "v2:" + base64(40),
                base64(40),
                "",
                "v1:",
                "v1:not base64!",
                "v1:" + base64(40) + "\n",
                "v1:" + base64(27),
            )
    }
}
