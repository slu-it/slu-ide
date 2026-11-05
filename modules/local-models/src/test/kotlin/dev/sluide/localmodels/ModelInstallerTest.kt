package dev.sluide.localmodels

import dev.sluide.backgroundtasks.BackgroundTasks
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelInstallerTest {
    @TempDir
    lateinit var dir: Path

    /** Key "a" needs a.bin and shared.bin, key "b" needs sub/b.bin and shared.bin; each file is 4 bytes. */
    private val paths = mutableMapOf("a" to listOf("a.bin", "shared.bin"), "b" to listOf("sub/b.bin", "shared.bin"))
    private val specs =
        object : ModelSpecs<String> {
            override fun files(key: String) =
                paths.getValue(key).map { ModelFile(it, URI("http://localhost/$it"), 4, "") }

            override fun folder(key: String): Path = dir

            override fun installed(key: String) =
                files(key).all { Files.isRegularFile(dir.resolve(it.path)) && Files.size(dir.resolve(it.path)) == 4L }

            override fun taskName(
                key: String,
                percent: Int,
            ) = "Downloading $key $percent %"
        }

    private val queued = ArrayDeque<Runnable>()
    private val calls = mutableListOf<List<String>>()
    private var work: (List<ModelFile>, (Double) -> Unit, () -> Boolean) -> Unit = { _, _, _ -> }
    private var closed = false
    private val downloader =
        object : ModelDownloader() {
            override fun download(
                files: List<ModelFile>,
                folder: Path,
                progress: (Double) -> Unit,
                cancelled: () -> Boolean,
            ) {
                calls += files.map { it.path }
                work(files, progress, cancelled)
            }

            override fun close() {
                closed = true
            }
        }
    private val installer by lazy { ModelInstaller(specs, downloader, { queued += it }, { it.run() }) }

    private fun runQueued() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    private fun install(vararg paths: String) =
        paths.forEach {
            Files.createDirectories(dir.resolve(it).parent)
            dir.resolve(it).writeBytes(ByteArray(4))
        }

    @Test
    fun `the first state of a key comes from its specs`() {
        install("a.bin", "shared.bin")
        assertEquals(InstallState.Installed, installer.state("a").get())
        assertEquals(InstallState.NotInstalled, installer.state("b").get())
    }

    @Test
    fun `a download goes through downloading to installed and renames its row in steps of 5 percent`() {
        val log = mutableListOf<String>()
        work = { _, progress, _ ->
            progress(0.13)
            progress(0.47)
            install("a.bin", "shared.bin")
            assertEquals(InstallState.Downloading(0.47), installer.state("a").get())
        }

        installer.download("a", BackgroundTasks.forTests { log += it })
        assertEquals(InstallState.Downloading(0.0), installer.state("a").get())
        runQueued()

        assertEquals(InstallState.Installed, installer.state("a").get())
        assertEquals(listOf(listOf("a.bin", "shared.bin")), calls)
        assertTrue(log.any { it.endsWith("started: Downloading a 0 %") }, log.toString())
        assertTrue(log.any { it.endsWith("Downloading a 0 % → Downloading a 10 %") }, log.toString())
        assertTrue(log.any { it.endsWith("Downloading a 10 % → Downloading a 45 %") }, log.toString())
        assertTrue(log.any { it.contains("finished in") }, log.toString())
    }

    @Test
    fun `a download that completes another key through a shared file shows it installed`() {
        install("a.bin")
        assertEquals(InstallState.NotInstalled, installer.state("a").get())
        work = { _, _, _ -> install("sub/b.bin", "shared.bin") }

        installer.download("b", null)
        runQueued()

        assertEquals(InstallState.Installed, installer.state("b").get())
        assertEquals(InstallState.Installed, installer.state("a").get())
    }

    @Test
    fun `a download that fails after fetching a shared file fails and still completes the other key`() {
        install("a.bin")
        assertEquals(InstallState.NotInstalled, installer.state("a").get())
        work = { _, _, _ ->
            install("shared.bin")
            throw ModelDownloadException("Download of sub/b.bin failed: checksum mismatch")
        }

        installer.download("b", null)
        runQueued()

        assertIs<InstallState.Failed>(installer.state("b").get())
        assertEquals(InstallState.Installed, installer.state("a").get())
    }

    @Test
    fun `no second download of a key starts while one runs`() {
        installer.download("a", null)
        installer.download("a", null)
        runQueued()
        assertEquals(1, calls.size)
    }

    @Test
    fun `cancelling stops the work, ends the row as cancelled and shows not installed`() {
        val log = mutableListOf<String>()
        work = { _, _, cancelled -> if (cancelled()) throw CancellationException() }
        installer.download("b", BackgroundTasks.forTests { log += it })
        installer.cancel("b")
        runQueued()

        assertEquals(InstallState.NotInstalled, installer.state("b").get())
        assertTrue(log.any { it.contains("cancelled after") }, log.toString())
    }

    @Test
    fun `a download failure shows its message and the task fails with it`() {
        val log = mutableListOf<String>()
        work = { _, _, _ -> throw ModelDownloadException("Download of x failed: checksum mismatch") }
        installer.download("a", BackgroundTasks.forTests { log += it })
        runQueued()

        val state = installer.state("a").get()
        assertEquals("Download of x failed: checksum mismatch", assertIs<InstallState.Failed>(state).message)
        assertTrue(log.any { it.contains("failed after") && it.contains("checksum mismatch") }, log.toString())
    }

    @Test
    fun `an unexpected error fails the download with its message`() {
        work = { _, _, _ -> throw IllegalStateException("boom") }
        installer.download("a", null)
        runQueued()
        assertEquals(InstallState.Failed("Download failed: boom"), installer.state("a").get())
    }

    @Test
    fun `a download that did not leave the files ends not installed`() {
        installer.download("a", null)
        runQueued()
        assertEquals(InstallState.NotInstalled, installer.state("a").get())
    }

    @Test
    fun `deleting removes the files of the key and stray part files, and every state is read again`() {
        install("a.bin", "sub/b.bin", "shared.bin", "a.bin-1.part", "sub/b.bin-2.part")
        assertEquals(InstallState.Installed, installer.state("b").get())

        installer.delete("a")
        runQueued()

        assertFalse(dir.resolve("a.bin").exists())
        assertFalse(dir.resolve("shared.bin").exists())
        assertFalse(dir.resolve("a.bin-1.part").exists())
        assertTrue(dir.resolve("sub/b.bin-2.part").exists(), "only the folders of the deleted files are cleaned")
        assertEquals(InstallState.NotInstalled, installer.state("a").get())
        assertEquals(InstallState.NotInstalled, installer.state("b").get(), "it lost the shared file")
    }

    @Test
    fun `a delete keeps what its keep names and the part files while a download runs`() {
        install("a.bin", "shared.bin", "a.bin-1.part")
        val asked = mutableListOf<Boolean>()
        installer.download("b", null)
        installer.delete("a") { running ->
            asked += running
            setOf("shared.bin")
        }

        // The delete runs while the download is under way.
        queued.removeLast().run()

        assertEquals(listOf(true), asked)
        assertFalse(dir.resolve("a.bin").exists())
        assertTrue(dir.resolve("shared.bin").exists())
        assertTrue(dir.resolve("a.bin-1.part").exists(), "it may be the running download's")
        assertEquals(InstallState.NotInstalled, installer.state("a").get())
        assertEquals(InstallState.Downloading(0.0), installer.state("b").get(), "a download keeps its state")
    }

    @ParameterizedTest
    @ValueSource(strings = ["../outside.bin", "/outside.bin", "", "."])
    fun `a delete of a path outside the folder or of the folder itself fails without deleting`(path: String) {
        val folder = dir.resolve("models")
        install("outside.bin", "models/a.bin")
        val absolute = dir.resolve("outside.bin").toString()
        paths["a"] = listOf("a.bin", if (path == "/outside.bin") absolute else path)
        val inFolder =
            ModelInstaller(
                object : ModelSpecs<String> by specs {
                    override fun folder(key: String): Path = folder
                },
                downloader,
                { queued += it },
                { it.run() },
            )

        inFolder.delete("a")
        runQueued()

        assertIs<InstallState.Failed>(inFolder.state("a").get())
        assertTrue(dir.resolve("outside.bin").exists())
        assertTrue(folder.resolve("a.bin").exists(), "nothing is deleted when a path is wrong")
    }

    @Test
    fun `a key that downloads is not deleted`() {
        install("a.bin", "shared.bin")
        installer.download("a", null)
        installer.delete("a")
        assertEquals(1, queued.size, "only the download")
    }

    @Test
    fun `closing closes the downloader`() {
        installer.close()
        assertTrue(closed)
    }
}
