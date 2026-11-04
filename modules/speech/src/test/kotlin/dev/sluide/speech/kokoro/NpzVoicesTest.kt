package dev.sluide.speech.kokoro

import dev.sluide.speech.engine.SpeechException
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NpzVoicesTest {
    @TempDir
    lateinit var dir: Path

    private fun header(
        descr: String = "<f4",
        shape: String = "(510, 1, 256)",
    ) = "{'descr': '$descr', 'fortran_order': False, 'shape': $shape, }"

    /** Row r is filled with the value r + base. */
    private fun npy(
        version: Int,
        header: String = header(),
        rows: Int = 510,
        base: Float = 0f,
    ): ByteArray {
        val text = (header + " ".repeat(15) + "\n").toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x93.toByte()) + "NUMPY".toByteArray() + byteArrayOf(version.toByte(), 0))
        val length = ByteBuffer.allocate(if (version == 1) 2 else 4).order(ByteOrder.LITTLE_ENDIAN)
        if (version == 1) length.putShort(text.size.toShort()) else length.putInt(text.size)
        out.write(length.array())
        out.write(text)
        val data = ByteBuffer.allocate(rows * 256 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (r in 0 until rows) repeat(256) { data.putFloat(r + base) }
        out.write(data.array())
        return out.toByteArray()
    }

    private fun npz(vararg entries: Pair<String, ByteArray>): Path {
        val file = dir.resolve("voices.bin")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `reads version 1 and 2 headers and picks the row of the token count`() {
        val voices = NpzVoices(npz("v1.npy" to npy(1), "v2.npy" to npy(2, base = 1000f)))
        val one = voices.style("v1", 5)
        assertEquals(256, one.size)
        assertEquals(4f, one[0])
        assertEquals(1004f, voices.style("v2", 5)[255])
    }

    @Test
    fun `the row is clamped to the first and the last`() {
        val voices = NpzVoices(npz("v1.npy" to npy(1)))
        assertEquals(0f, voices.style("v1", 0)[0])
        assertEquals(0f, voices.style("v1", 1)[0])
        assertEquals(509f, voices.style("v1", 510)[0])
        assertEquals(509f, voices.style("v1", 9999)[0])
    }

    @Test
    fun `unexpected data is refused`() {
        val voices =
            NpzVoices(
                npz(
                    "descr.npy" to npy(1, header(descr = "<f8")),
                    "shape.npy" to npy(1, header(shape = "(511, 1, 256)")),
                    "short.npy" to npy(1, rows = 3),
                    "magic.npy" to "nonsense bytes here".toByteArray(),
                ),
            )
        for (name in listOf("descr", "shape", "short", "magic")) {
            val e = assertFailsWith<SpeechException> { voices.style(name, 1) }
            assertEquals("Unexpected Kokoro voice data", e.message)
        }
    }

    @Test
    fun `an unknown voice or a missing file is a speech exception`() {
        assertFailsWith<SpeechException> { NpzVoices(npz("v1.npy" to npy(1))).style("nope", 1) }
        assertFailsWith<SpeechException> { NpzVoices(dir.resolve("absent")).style("v1", 1) }
    }
}
