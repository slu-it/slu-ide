package dev.sluide.speech.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PcmTest {
    @Test
    fun `floats become 16-bit values, clamped, NaN silent`() {
        val samples = floatArrayOf(0f, 1f, -1f, 0.5f, 1.5f, -7f, Float.NaN, Float.POSITIVE_INFINITY)

        assertContentEquals(
            shortArrayOf(0, 32767, -32767, 16384, 32767, -32767, 0, 32767),
            Pcm.toShorts(samples),
        )
    }

    @Test
    fun `bytes are signed little-endian, two per sample`() {
        val bytes = Pcm.toLittleEndianBytes(floatArrayOf(1f, -1f, 0.5f))

        assertContentEquals(byteArrayOf(0xFF.toByte(), 0x7F, 0x01, 0x80.toByte(), 0x00, 0x40), bytes)
    }

    @Test
    fun `kept 16-bit values give back the same values`() {
        val pcm = ShortArray(65535) { (it - 32767).toShort() }

        val floats = Pcm.toFloats(pcm, 0, pcm.size)

        assertContentEquals(pcm, Pcm.toShorts(floats))
        assertEquals(-1f, floats.first())
        assertEquals(1f, floats.last())
    }

    @Test
    fun `a range of the values converts alone`() {
        val floats = Pcm.toFloats(shortArrayOf(0, 32767, -32767, 0), 1, 3)

        assertContentEquals(floatArrayOf(1f, -1f), floats)
    }
}
