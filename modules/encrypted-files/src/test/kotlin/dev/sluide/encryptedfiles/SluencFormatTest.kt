package dev.sluide.encryptedfiles

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.security.SecureRandom

/** The header of a `.sluenc` file as a reader sees it, with hostile headers (ADR 0162). */
class SluencFormatTest {
    private val codec =
        SluencCodec(SecureRandom(), { password, _, _ -> ByteArray(32) { (password.size + 1).toByte() } })
    private val params = KdfParams(64, 1, 1)
    private val password = "correct horse battery".toCharArray()

    private val unknownSlot = byteArrayOf(9, 0, 20) + ByteArray(20) { 0x5A }

    private fun created(plain: String = "secret") = codec.create(password, plain.toByteArray(), params)

    private fun header(
        count: Int,
        vararg slots: ByteArray,
    ) = SluencFormat.MAGIC + byteArrayOf(1, count.toByte()) + slots.fold(ByteArray(0), ByteArray::plus)

    private fun failureOf(file: ByteArray) = assertThrows<SluencException> { SluencFormat.parse(file) }

    @Test
    fun `of several password slots the first is used`() {
        val first = created()
        val second = created("other")
        val file = codec.seal(header(2, first.key.slot, second.key.slot), "secret".toByteArray(), first.key)

        assertArrayEquals(first.key.slot, SluencFormat.parse(file).slot.encoded)
        codec.unlock(file, password).close()
    }

    @Test
    fun `eight slots are read, nine are damage`() {
        val made = created()
        val eight = Array(7) { unknownSlot } + made.key.slot
        val nine = Array(8) { unknownSlot } + made.key.slot

        val parsed = SluencFormat.parse(codec.seal(header(8, *eight), "secret".toByteArray(), made.key))

        assertEquals(8, parsed.slotCount)
        assertEquals(
            SluencFailure.DAMAGED,
            failureOf(codec.seal(header(9, *nine), "secret".toByteArray(), made.key)).failure,
        )
    }

    @Test
    fun `no slots is damage`() {
        val made = created()

        assertEquals(
            SluencFailure.DAMAGED,
            failureOf(codec.seal(header(0, made.key.slot), "secret".toByteArray(), made.key)).failure,
        )
    }

    @Test
    fun `version 0 is damage, not a readable file`() {
        val file = created().bytes
        file[SluencFormat.MAGIC.size] = 0

        assertEquals(SluencFailure.DAMAGED, failureOf(file).failure)
    }

    @ParameterizedTest
    @CsvSource("6, DAMAGED", "7, DAMAGED", "8, NEWER_VERSION")
    fun `a file that ends in its fixed header`(
        length: Int,
        expected: SluencFailure,
    ) {
        // magic, version 2, one slot: the version is read as soon as the two fixed bytes are there
        val file = SluencFormat.MAGIC + byteArrayOf(2, 1)

        val failure = failureOf(file.copyOf(length))

        assertEquals(expected, failure.failure)
    }

    @Test
    fun `a newer version is named by its number`() {
        val file = SluencFormat.MAGIC + byteArrayOf(255.toByte(), 1)

        assertEquals(255, failureOf(file).formatVersion)
    }
}
