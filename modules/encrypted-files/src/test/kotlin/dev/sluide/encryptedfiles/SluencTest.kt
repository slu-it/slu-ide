package dev.sluide.encryptedfiles

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.text.Normalizer

class SluencTest {
    private val params = KdfParams(64, 1, 1)
    private val password = "correct horse battery".toCharArray()

    private fun create(plain: String = "secret notes") =
        Sluenc.create(password, plain.toByteArray(), params).also { it.key.close() }.bytes

    private fun failureOf(block: () -> Unit): SluencException = assertThrows<SluencException> { block() }

    @ParameterizedTest
    @ValueSource(strings = ["", "secret notes", "pässwörd ✓ 秘密 🔑\r\nzweite Zeile\n"])
    fun `round trip`(plain: String) {
        val created = Sluenc.create(password, plain.toByteArray(), params)

        assertArrayEquals(plain.toByteArray(), Sluenc.decrypt(created.bytes, created.key))
        Sluenc.unlock(created.bytes, password).use { key ->
            assertArrayEquals(plain.toByteArray(), Sluenc.decrypt(created.bytes, key))
        }
    }

    @Test
    fun `magic, version, one password slot, body nonce and the sealed body`() {
        val bytes = create("12345")

        assertArrayEquals("SLUENC".toByteArray(), bytes.copyOfRange(0, 6))
        assertEquals(1, bytes[6].toInt())
        assertEquals(1, bytes[7].toInt())
        assertEquals(1, bytes[8].toInt())
        assertEquals(85, ((bytes[9].toInt() and 0xFF) shl 8) or (bytes[10].toInt() and 0xFF))
        assertEquals(8 + 88 + 12 + 5 + 16, bytes.size)
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("12345"))
    }

    @Test
    fun `wrong password`() {
        val bytes = create()

        assertEquals(
            SluencFailure.WRONG_PASSWORD,
            failureOf { Sluenc.unlock(bytes, "correct horse battery!".toCharArray()) }.failure,
        )
    }

    @Test
    fun `the caller's password array is neither kept nor zeroed`() {
        val bytes = create()
        val typed = password.copyOf()

        Sluenc.unlock(bytes, typed).close()

        assertArrayEquals(password, typed)
    }

    // Offsets: magic 0-5, version 6, slot count 7, slot type 8, slot length 9-10 (85), memory 11-14 (64),
    // passes 15-18 (1), parallelism 19 (1), salt 20-35, wrap nonce 36-47, wrapped DEK 48-95, body nonce 96-107,
    // body from 108 (-1: the last byte, in the tag).
    @ParameterizedTest(name = "byte {0} xor {1} -> {2}")
    @CsvSource(
        "0, 1, NOT_SLUENC",
        "5, 1, NOT_SLUENC",
        "6, 1, DAMAGED",
        "6, 3, NEWER_VERSION",
        "7, 255, DAMAGED",
        "7, 1, DAMAGED",
        // Another slot type is skipped by its length, which leaves no password slot.
        "8, 255, DAMAGED",
        // Longer than the rest of the file, then shorter than a password slot.
        "9, 1, DAMAGED",
        "10, 1, DAMAGED",
        "11, 128, UNSUPPORTED_PARAMETERS",
        "14, 1, WRONG_PASSWORD",
        "18, 1, UNSUPPORTED_PARAMETERS",
        "18, 2, WRONG_PASSWORD",
        "19, 2, WRONG_PASSWORD",
        "19, 255, UNSUPPORTED_PARAMETERS",
        "20, 1, WRONG_PASSWORD",
        "35, 1, WRONG_PASSWORD",
        "36, 1, WRONG_PASSWORD",
        "47, 1, WRONG_PASSWORD",
        "48, 1, WRONG_PASSWORD",
        "95, 1, WRONG_PASSWORD",
        "96, 1, DAMAGED",
        "107, 1, DAMAGED",
        "108, 1, DAMAGED",
        "-1, 1, DAMAGED",
    )
    fun `a flipped byte gives the documented failure`(
        offset: Int,
        mask: Int,
        expected: SluencFailure,
    ) {
        val bytes = create()
        val index = if (offset < 0) bytes.size + offset else offset
        bytes[index] = (bytes[index].toInt() xor mask).toByte()

        assertEquals(expected, failureOf { Sluenc.unlock(bytes, password) }.failure)
    }

    @ParameterizedTest
    @ValueSource(ints = [96, 107, 108, -1])
    fun `a flipped body byte under the file's own key is damage, not a key mismatch`(offset: Int) {
        val created = Sluenc.create(password, "secret notes".toByteArray(), params)
        val bytes = created.bytes
        val index = if (offset < 0) bytes.size + offset else offset
        bytes[index] = (bytes[index].toInt() xor 1).toByte()

        assertEquals(SluencFailure.DAMAGED, failureOf { Sluenc.decrypt(bytes, created.key) }.failure)
    }

    @ParameterizedTest
    @CsvSource(
        "0, NOT_SLUENC",
        "3, NOT_SLUENC",
        "6, DAMAGED",
        "7, DAMAGED",
        "8, DAMAGED",
        "10, DAMAGED",
        "50, DAMAGED",
        "95, DAMAGED",
        "96, DAMAGED",
        "123, DAMAGED",
        "-1, DAMAGED",
    )
    fun `a truncated file`(
        length: Int,
        expected: SluencFailure,
    ) {
        val bytes = create()
        val truncated = bytes.copyOf(if (length < 0) bytes.size + length else length)

        assertEquals(expected, failureOf { Sluenc.unlock(truncated, password) }.failure)
    }

    @Test
    fun `a foreign file is not a sluenc file`() {
        val openssl = "Salted__".toByteArray() + ByteArray(48) { it.toByte() }

        val failure = failureOf { Sluenc.unlock(openssl, password) }

        assertEquals(SluencFailure.NOT_SLUENC, failure.failure)
        assertEquals("Not a Thoreau encrypted file.", failure.message)
    }

    @Test
    fun `a newer version names its format`() {
        val bytes = create()
        bytes[6] = 2

        val failure = failureOf { Sluenc.unlock(bytes, password) }

        assertEquals(SluencFailure.NEWER_VERSION, failure.failure)
        assertEquals(2, failure.formatVersion)
        assertEquals("Written by a newer Thoreau (format 2).", failure.message)
    }

    @Test
    fun `encrypting twice gives different bytes under the same slot`() {
        val created = Sluenc.create(password, ByteArray(0), params)

        val first = Sluenc.encrypt("same".toByteArray(), created.key)
        val second = Sluenc.encrypt("same".toByteArray(), created.key)

        assertFalse(first.contentEquals(second))
        assertArrayEquals(created.bytes.copyOfRange(0, 96), first.copyOfRange(0, 96))
        assertArrayEquals(first.copyOfRange(0, 96), second.copyOfRange(0, 96))
        assertArrayEquals("same".toByteArray(), Sluenc.decrypt(first, created.key))
        Sluenc.unlock(second, password).use { assertArrayEquals("same".toByteArray(), Sluenc.decrypt(second, it)) }
    }

    @Test
    fun `an unlocked key writes the file's slot`() {
        val bytes = create()

        Sluenc.unlock(bytes, password).use { key ->
            assertArrayEquals(bytes.copyOfRange(0, 96), Sluenc.encrypt("other".toByteArray(), key).copyOfRange(0, 96))
        }
    }

    @Test
    fun `change password writes a new DEK under the new password`() {
        val old = Sluenc.create(password, "v1".toByteArray(), params)
        val newPassword = "a different passphrase".toCharArray()

        val changed = Sluenc.changePassword(old.bytes, password, newPassword, "v2".toByteArray(), params)

        Sluenc.unlock(changed.bytes, newPassword).use {
            assertArrayEquals("v2".toByteArray(), Sluenc.decrypt(changed.bytes, it))
        }
        assertEquals(SluencFailure.WRONG_PASSWORD, failureOf { Sluenc.unlock(changed.bytes, password) }.failure)
        assertEquals(SluencFailure.KEY_MISMATCH, failureOf { Sluenc.decrypt(changed.bytes, old.key) }.failure)
        assertFalse(old.key.rawDek.contentEquals(changed.key.rawDek))
        assertFalse(old.bytes.copyOfRange(20, 36).contentEquals(changed.bytes.copyOfRange(20, 36)), "new salt")
    }

    @Test
    fun `change password checks the current password first`() {
        val bytes = create()

        val failure =
            failureOf { Sluenc.changePassword(bytes, "not the password".toCharArray(), password, ByteArray(0), params) }

        assertEquals(SluencFailure.WRONG_PASSWORD, failure.failure)
    }

    @Test
    fun `a key of another file is a key mismatch`() {
        val other = Sluenc.create(password, "other".toByteArray(), params)

        assertEquals(SluencFailure.KEY_MISMATCH, failureOf { Sluenc.decrypt(create(), other.key) }.failure)
    }

    @Test
    fun `close zeroes the DEK, may be repeated, and a closed key cannot be used`() {
        val created = Sluenc.create(password, "secret".toByteArray(), params)
        val key = created.key
        assertTrue(key.rawDek.any { it != 0.toByte() })

        key.close()
        key.close()

        assertTrue(key.rawDek.all { it == 0.toByte() })
        assertThrows<IllegalStateException> { Sluenc.encrypt("x".toByteArray(), key) }
        assertThrows<IllegalStateException> { Sluenc.decrypt(created.bytes, key) }
    }

    @Test
    fun `composed and decomposed accents are the same password`() {
        val composed = Normalizer.normalize("Café-Passwort-12", Normalizer.Form.NFC).toCharArray()
        val decomposed = Normalizer.normalize("Café-Passwort-12", Normalizer.Form.NFD).toCharArray()
        assertFalse(composed.contentEquals(decomposed))
        val bytes = Sluenc.create(composed, "secret".toByteArray(), params).also { it.key.close() }.bytes

        Sluenc.unlock(bytes, decomposed).use { assertArrayEquals("secret".toByteArray(), Sluenc.decrypt(bytes, it)) }
    }

    // Pins the fast path of Argon2id.utf8: a password of these characters only is taken as NFC without a String.
    @Test
    fun `every character below U+0300 is NFC on its own`() {
        for (c in '\u0000' until Argon2id.FIRST_NON_NFC_SAFE) {
            assertTrue(Normalizer.isNormalized(c.toString(), Normalizer.Form.NFC), "U+%04X".format(c.code))
        }
    }

    @Test
    fun `parameters above the caps are refused when creating`() {
        assertThrows<IllegalArgumentException> { Sluenc.create(password, ByteArray(0), KdfParams(64, 11, 1)) }
    }

    @Test
    fun `the default parameters derive in reasonable time`() {
        val started = System.nanoTime()
        val created = Sluenc.create(password, "secret".toByteArray())
        val millis = (System.nanoTime() - started) / 1_000_000
        created.key.close()

        assertTrue(millis < 5_000, "took $millis ms")
        assertArrayEquals(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 3, 4), created.bytes.copyOfRange(11, 20))
    }
}
