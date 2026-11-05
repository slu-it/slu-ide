package dev.sluide.speech.install

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.localmodels.InstallState
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Kokoro's side of the installer; the generic rules are in local-models' `ModelInstallerTest`. */
class ModelInstallerTest {
    @TempDir
    lateinit var dir: Path

    private val store by lazy { ModelStore(dir) }
    private val queued = ArrayDeque<Runnable>()
    private val fetched = mutableListOf<List<String>>()
    private var work: (List<String>, (Double) -> Unit) -> Unit = { _, _ -> }
    private val installer by lazy {
        ModelInstaller(
            store,
            fakeDownloader { files, progress, _ ->
                val paths = files.map { it.path }
                fetched += paths
                work(paths, progress)
            },
            { queued += it },
            { it.run() },
        )
    }

    private fun runQueued() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    private fun sparse(
        file: Path,
        bytes: Long,
    ) = RandomAccessFile(file.toFile(), "rw").use { it.setLength(bytes) }

    private fun install(v: KokoroVariant) = sparse(store.modelFile(v), v.bytes)

    @Test
    fun `the first state comes from the store`() {
        sparse(store.voicesFile(), KokoroVoicesFile.BYTES)
        install(KokoroVariant.INT8)
        assertEquals(InstallState.Installed, installer.state(KokoroVariant.INT8).get())
        assertEquals(InstallState.NotInstalled, installer.state(KokoroVariant.FP32).get())
    }

    @Test
    fun `a download fetches the model and the voices file and names its row as the Kokoro model`() {
        val log = mutableListOf<String>()
        work = { _, progress ->
            progress(0.4)
            sparse(store.voicesFile(), KokoroVoicesFile.BYTES)
            install(KokoroVariant.FP32)
        }

        installer.download(KokoroVariant.FP32, BackgroundTasks.forTests { log += it })
        runQueued()

        assertEquals(InstallState.Installed, installer.state(KokoroVariant.FP32).get())
        assertEquals(listOf(listOf("kokoro-v1.0.onnx", "voices-v1.0.bin")), fetched)
        assertTrue(log.any { it.endsWith("started: Downloading Kokoro model 0 %") }, log.toString())
        assertTrue(
            log.any { it.endsWith("Downloading Kokoro model 0 % → Downloading Kokoro model 40 %") },
            log.toString(),
        )
    }

    @Test
    fun `deleting one variant keeps the voices file for another, deleting the last removes it with stale parts`() {
        sparse(store.voicesFile(), KokoroVoicesFile.BYTES)
        KokoroVariant.entries.take(2).forEach(::install)
        val part = dir.resolve("voices-v1.0.bin-123.part")
        part.toFile().writeText("x")

        installer.delete(KokoroVariant.FP32)
        runQueued()

        assertFalse(store.modelFile(KokoroVariant.FP32).exists())
        assertEquals(InstallState.NotInstalled, installer.state(KokoroVariant.FP32).get())
        assertEquals(InstallState.Installed, installer.state(KokoroVariant.FP16).get())

        installer.delete(KokoroVariant.FP16)
        runQueued()

        assertFalse(store.voicesFile().exists())
        assertFalse(part.exists())
        assertEquals(InstallState.NotInstalled, installer.state(KokoroVariant.FP16).get())
    }

    @Test
    fun `a delete during another download keeps the voices file and both end in the state on disk`() {
        sparse(store.voicesFile(), KokoroVoicesFile.BYTES)
        install(KokoroVariant.FP32)
        work = { _, _ -> install(KokoroVariant.FP16) }
        installer.download(KokoroVariant.FP16, null)
        installer.delete(KokoroVariant.FP32)

        // The delete runs while the download is under way.
        queued.removeLast().run()
        assertTrue(store.voicesFile().toFile().exists(), "the download relies on it")
        assertEquals(InstallState.NotInstalled, installer.state(KokoroVariant.FP32).get())

        runQueued()
        assertEquals(InstallState.Installed, installer.state(KokoroVariant.FP16).get())
    }
}
