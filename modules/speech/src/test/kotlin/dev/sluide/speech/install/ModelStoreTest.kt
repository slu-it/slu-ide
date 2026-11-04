package dev.sluide.speech.install

import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelStoreTest {
    @TempDir
    lateinit var dir: Path

    private val store by lazy { ModelStore(dir) }

    private fun sparse(
        file: Path,
        bytes: Long,
    ) = RandomAccessFile(file.toFile(), "rw").use { it.setLength(bytes) }

    private fun install(v: KokoroVariant) = sparse(store.modelFile(v), v.bytes)

    private fun installVoices() = sparse(store.voicesFile(), KokoroVoicesFile.BYTES)

    @Test
    fun `a variant is installed when model and voices have the expected size`() {
        assertFalse(store.installed(KokoroVariant.FP32))
        install(KokoroVariant.FP32)
        assertFalse(store.installed(KokoroVariant.FP32), "voices missing")
        installVoices()
        assertTrue(store.installed(KokoroVariant.FP32))
        assertFalse(store.installed(KokoroVariant.INT8))
        sparse(store.modelFile(KokoroVariant.INT8), 10)
        assertFalse(store.installed(KokoroVariant.INT8), "wrong size")
    }

    @Test
    fun `missing bytes count the model and the voices file that are not there`() {
        val v = KokoroVariant.FP16
        assertEquals(v.bytes + KokoroVoicesFile.BYTES, store.missingBytes(v))
        installVoices()
        assertEquals(v.bytes, store.missingBytes(v))
        install(v)
        assertEquals(0, store.missingBytes(v))
    }

    @Test
    fun `deleting keeps the voices file while another variant is installed`() {
        install(KokoroVariant.FP32)
        install(KokoroVariant.INT8)
        installVoices()

        store.delete(KokoroVariant.FP32)
        assertFalse(store.modelFile(KokoroVariant.FP32).exists())
        assertTrue(store.voicesFile().exists())
        assertTrue(store.installed(KokoroVariant.INT8))

        store.delete(KokoroVariant.INT8)
        assertFalse(store.voicesFile().exists())
    }

    @Test
    fun `keeping the voices file and stale part files while a download runs, removing them otherwise`() {
        install(KokoroVariant.FP32)
        installVoices()
        val part = dir.resolve("voices-v1.0.bin-123.part")
        part.toFile().writeText("x")

        store.delete(KokoroVariant.FP32, keepVoices = true)
        assertTrue(store.voicesFile().exists())
        assertTrue(part.exists())

        store.delete(KokoroVariant.FP32)
        assertFalse(store.voicesFile().exists())
        assertFalse(part.exists())
    }
}
