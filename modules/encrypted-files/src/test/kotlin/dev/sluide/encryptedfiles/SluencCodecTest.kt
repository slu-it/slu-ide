package dev.sluide.encryptedfiles

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.ByteBuffer
import java.security.SecureRandom

class SluencCodecTest {
    /** Every array the fake KDF returned, and every array the random filled (DEKs, salts, nonces). */
    private val derived = mutableListOf<ByteArray>()
    private val filled = mutableListOf<ByteArray>()
    private val keys = mutableListOf<FileKey>()

    private val random =
        object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                super.nextBytes(bytes)
                filled += bytes
            }
        }

    // A key per password length, so a password of another length is a wrong one.
    private val codec =
        SluencCodec(
            random,
            { password, _, _ -> ByteArray(32) { (password.size + 1).toByte() }.also { derived += it } },
        ) {
            keys += it
        }
    private val password = "correct horse battery".toCharArray()
    private val params = KdfParams(64, 1, 1)

    private fun assertZeroed(arrays: List<ByteArray>) {
        assertTrue(arrays.isNotEmpty())
        arrays.forEach { array -> assertTrue(array.all { it == 0.toByte() }, "not zeroed") }
    }

    /** A file whose slot asks for these parameters (memory and passes as unsigned 32-bit numbers). */
    private fun fileWith(
        memoryKiB: Long,
        passes: Long,
        parallelism: Int,
    ): ByteArray {
        val bytes = codec.create(password, "secret".toByteArray(), params).also { it.key.close() }.bytes
        ByteBuffer
            .wrap(bytes)
            .putInt(11, memoryKiB.toInt())
            .putInt(15, passes.toInt())
            .put(19, parallelism.toByte())
        derived.clear()
        return bytes
    }

    // Each refused row breaks exactly one cap; each accepted row sits on that cap. An accepted slot runs the KDF once
    // and then fails its tag, since the parameters are part of the wrap's associated data.
    @ParameterizedTest(name = "memory {0}, passes {1}, parallelism {2} -> {3}")
    @CsvSource(
        "1048576, 1, 1, WRONG_PASSWORD",
        "1048577, 1, 1, UNSUPPORTED_PARAMETERS",
        "4294967295, 1, 1, UNSUPPORTED_PARAMETERS",
        "32, 1, 4, WRONG_PASSWORD",
        "31, 1, 4, UNSUPPORTED_PARAMETERS",
        "64, 20, 1, WRONG_PASSWORD",
        "64, 21, 1, UNSUPPORTED_PARAMETERS",
        "64, 4294967295, 1, UNSUPPORTED_PARAMETERS",
        "64, 0, 1, UNSUPPORTED_PARAMETERS",
        "128, 1, 16, WRONG_PASSWORD",
        "136, 1, 17, UNSUPPORTED_PARAMETERS",
        "64, 1, 0, UNSUPPORTED_PARAMETERS",
    )
    fun `parameter caps are checked before deriving a key`(
        memoryKiB: Long,
        passes: Long,
        parallelism: Int,
        expected: SluencFailure,
    ) {
        val bytes = fileWith(memoryKiB, passes, parallelism)

        val failure = assertThrows<SluencException> { codec.unlock(bytes, password) }.failure

        assertEquals(expected, failure)
        assertEquals(if (expected == SluencFailure.WRONG_PASSWORD) 1 else 0, derived.size)
    }

    /** A slot of type 9 with 20 bytes of its own, which this reader does not know. */
    private val unknownSlot = byteArrayOf(9, 0, 20) + ByteArray(20) { 0x5A }

    private fun header(vararg slots: ByteArray) =
        SluencFormat.MAGIC + byteArrayOf(1, slots.size.toByte()) + slots.reduce(ByteArray::plus)

    /** A file with [unknownSlot] before its password slot, and the key it was written with. */
    private fun fileWithUnknownSlot(plain: String = "secret"): Pair<ByteArray, FileKey> {
        val created = codec.create(password, ByteArray(0), params)
        return codec.seal(header(unknownSlot, created.key.slot), plain.toByteArray(), created.key) to created.key
    }

    @Test
    fun `a slot of an unknown type is skipped by its length`() {
        val (file, _) = fileWithUnknownSlot()

        codec.unlock(file, password).use { key ->
            assertArrayEquals("secret".toByteArray(), codec.decrypt(file, key))
        }
    }

    @Test
    fun `saving keeps slots of unknown types`() {
        val (file, _) = fileWithUnknownSlot()
        val headerLength = 8 + unknownSlot.size + SluencFormat.PASSWORD_SLOT_BYTES

        val saved = codec.unlock(file, password).use { codec.encrypt("v2".toByteArray(), it) }

        assertArrayEquals(file.copyOfRange(0, headerLength), saved.copyOfRange(0, headerLength))
        codec.unlock(saved, password).use { assertArrayEquals("v2".toByteArray(), codec.decrypt(saved, it)) }
    }

    // The body's associated data is the whole header: a change to a slot the reader skips, or to the slot list, is
    // damage even though the password slot still unwraps.
    @ParameterizedTest
    @ValueSource(strings = ["byte in the unknown slot", "unknown slot dropped", "slots swapped"])
    fun `a changed header outside the password slot is damage`(change: String) {
        val (file, key) = fileWithUnknownSlot()
        val passwordSlot = key.slot
        val body = file.copyOfRange(8 + unknownSlot.size + passwordSlot.size, file.size)
        val changed =
            when (change) {
                "byte in the unknown slot" -> file.copyOf().also { it[8 + 5] = (it[8 + 5].toInt() xor 1).toByte() }
                "unknown slot dropped" -> header(passwordSlot) + body
                else -> header(passwordSlot, unknownSlot) + body
            }

        assertEquals(SluencFailure.DAMAGED, assertThrows<SluencException> { codec.unlock(changed, password) }.failure)
        assertEquals(SluencFailure.DAMAGED, assertThrows<SluencException> { codec.decrypt(changed, key) }.failure)
    }

    @Test
    fun `a file with only unknown slots is damaged`() {
        val file = header(unknownSlot) + ByteArray(12 + 16)

        val failure = assertThrows<SluencException> { codec.unlock(file, password) }.failure

        assertEquals(SluencFailure.DAMAGED, failure)
        assertEquals(0, derived.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["create", "unlock", "wrong password", "change password"])
    fun `every derived key is zeroed`(operation: String) {
        val file = codec.create(password, "secret".toByteArray(), params).also { it.key.close() }.bytes
        derived.clear()

        when (operation) {
            "create" -> codec.create(password, "secret".toByteArray(), params).key.close()
            "unlock" -> codec.unlock(file, password).close()
            "wrong password" -> assertThrows<SluencException> { codec.unlock(file, "wrong".toCharArray()) }
            else -> codec.changePassword(file, password, "new password".toCharArray(), ByteArray(0), params).key.close()
        }

        assertZeroed(derived)
    }

    @Test
    fun `a create that fails zeroes its new DEK`() {
        val failing = SluencCodec(random, { _, _, _ -> throw IllegalStateException("out of memory") })

        assertThrows<IllegalStateException> { failing.create(password, "secret".toByteArray(), params) }

        assertZeroed(filled.filter { it.size == SluencFormat.KEY_BYTES })
    }

    @Test
    fun `an unlock whose body fails zeroes the unwrapped DEK`() {
        val file = codec.create(password, "secret".toByteArray(), params).also { it.key.close() }.bytes
        file[file.size - 1] = (file[file.size - 1].toInt() xor 1).toByte()

        assertEquals(SluencFailure.DAMAGED, assertThrows<SluencException> { codec.unlock(file, password) }.failure)

        assertEquals(2, keys.size)
        assertZeroed(listOf(keys.last().rawDek))
    }
}
