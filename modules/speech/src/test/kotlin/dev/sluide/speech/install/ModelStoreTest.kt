package dev.sluide.speech.install

import dev.sluide.localmodels.ModelFile
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.Path
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
    fun `a variant needs its model and the shared voices file from the pinned release, both in the root`() {
        val release = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.1/"
        val v = KokoroVariant.INT8
        val voices = "voices-v1.0.bin"
        assertEquals(
            listOf(
                ModelFile(v.fileName, URI(release + v.fileName), v.bytes, v.sha256),
                ModelFile(voices, URI(release + voices), KokoroVoicesFile.BYTES, KokoroVoicesFile.SHA256),
            ),
            store.files(KokoroVariant.INT8),
        )
        assertEquals(dir, store.folder(KokoroVariant.INT8))
        assertEquals("Downloading Kokoro model 35 %", store.taskName(KokoroVariant.INT8, 35))
    }

    @Test
    fun `a delete keeps the voices file while another variant is installed or a download runs`() {
        install(KokoroVariant.FP32)
        installVoices()
        assertEquals(emptySet(), store.keptOnDelete(KokoroVariant.FP32, downloadsRunning = false))
        assertEquals(setOf("voices-v1.0.bin"), store.keptOnDelete(KokoroVariant.FP32, downloadsRunning = true))

        install(KokoroVariant.INT8)
        assertEquals(setOf("voices-v1.0.bin"), store.keptOnDelete(KokoroVariant.FP32, downloadsRunning = false))
    }
}
