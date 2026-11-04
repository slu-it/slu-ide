package dev.sluide.speech.install

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.speech.engine.SpeechException
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelInstallerTest {
    @TempDir
    lateinit var dir: Path

    private val store by lazy { ModelStore(dir) }
    private val queued = ArrayDeque<Runnable>()
    private val calls = mutableListOf<KokoroVariant>()
    private var work: DownloadWork = { _, _, _ -> }
    private val installer by lazy {
        ModelInstaller(
            store,
            { v, p, c ->
                calls += v
                work(v, p, c)
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

    @Test
    fun `the first state comes from the store`() {
        sparse(store.voicesFile(), KokoroVoicesFile.BYTES)
        sparse(store.modelFile(KokoroVariant.INT8), KokoroVariant.INT8.bytes)
        assertEquals(InstallState.Installed, installer.state(KokoroVariant.INT8).get())
        assertEquals(InstallState.NotInstalled, installer.state(KokoroVariant.FP32).get())
    }

    @Test
    fun `a download goes through downloading to installed and names its row by whole percent`() {
        val log = mutableListOf<String>()
        val tasks = BackgroundTasks.forTests { log += it }
        work = { _, progress, _ ->
            progress(0.4)
            sparse(store.voicesFile(), KokoroVoicesFile.BYTES)
            sparse(store.modelFile(KokoroVariant.FP32), KokoroVariant.FP32.bytes)
            assertEquals(InstallState.Downloading(0.4), installer.state(KokoroVariant.FP32).get())
        }

        installer.download(KokoroVariant.FP32, tasks)
        assertEquals(InstallState.Downloading(0.0), installer.state(KokoroVariant.FP32).get())
        runQueued()

        assertEquals(InstallState.Installed, installer.state(KokoroVariant.FP32).get())
        assertTrue(log.any { it.endsWith("started: Downloading Kokoro model 0 %") }, log.toString())
        assertTrue(
            log.any { it.endsWith("Downloading Kokoro model 0 % → Downloading Kokoro model 40 %") },
            log.toString(),
        )
        assertTrue(log.any { it.contains("finished in") }, log.toString())
    }

    @Test
    fun `no second download starts while one runs`() {
        installer.download(KokoroVariant.FP32, null)
        installer.download(KokoroVariant.FP32, null)
        runQueued()
        assertEquals(listOf(KokoroVariant.FP32), calls)
    }

    @Test
    fun `cancelling stops the work, ends the row as cancelled and shows not installed`() {
        val log = mutableListOf<String>()
        work = { _, _, cancelled -> if (cancelled()) throw CancellationException() }
        installer.download(KokoroVariant.FP16, BackgroundTasks.forTests { log += it })
        installer.cancel(KokoroVariant.FP16)
        runQueued()

        assertEquals(InstallState.NotInstalled, installer.state(KokoroVariant.FP16).get())
        assertTrue(log.any { it.contains("cancelled after") }, log.toString())
    }

    @Test
    fun `a failure shows its message and the task fails with it`() {
        val log = mutableListOf<String>()
        work = { _, _, _ -> throw SpeechException("Download of x failed: checksum mismatch") }
        installer.download(KokoroVariant.FP32, BackgroundTasks.forTests { log += it })
        runQueued()

        val state = installer.state(KokoroVariant.FP32).get()
        assertEquals("Download of x failed: checksum mismatch", assertIs<InstallState.Failed>(state).message)
        assertTrue(log.any { it.contains("failed after") && it.contains("checksum mismatch") }, log.toString())
    }

    @Test
    fun `deleting one variant leaves another installed`() {
        sparse(store.voicesFile(), KokoroVoicesFile.BYTES)
        KokoroVariant.entries.take(2).forEach { sparse(store.modelFile(it), it.bytes) }

        installer.delete(KokoroVariant.FP32)
        runQueued()

        assertEquals(InstallState.NotInstalled, installer.state(KokoroVariant.FP32).get())
        assertEquals(InstallState.Installed, installer.state(KokoroVariant.FP16).get())
    }

    @Test
    fun `a delete during another download keeps the voices file and both end in the state on disk`() {
        sparse(store.voicesFile(), KokoroVoicesFile.BYTES)
        sparse(store.modelFile(KokoroVariant.FP32), KokoroVariant.FP32.bytes)
        work = { v, _, _ -> sparse(store.modelFile(v), v.bytes) }
        installer.download(KokoroVariant.FP16, null)
        installer.delete(KokoroVariant.FP32)

        // The delete runs while the download is under way.
        queued.removeLast().run()
        assertTrue(store.voicesFile().toFile().exists(), "the download relies on it")
        assertEquals(InstallState.NotInstalled, installer.state(KokoroVariant.FP32).get())

        runQueued()
        assertEquals(InstallState.Installed, installer.state(KokoroVariant.FP16).get())
    }

    @Test
    fun `a download that did not leave the files ends not installed`() {
        installer.download(KokoroVariant.FP32, null)
        runQueued()
        assertEquals(InstallState.NotInstalled, installer.state(KokoroVariant.FP32).get())
    }
}
