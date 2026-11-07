package dev.sluide.localmodels

import dev.sluide.backgroundtasks.BackgroundTasks
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.concurrent.CancellationException
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolInstallerTest {
    @TempDir
    lateinit var dir: Path

    private val root by lazy { dir.resolve("tools/ty") }
    private val queued = ArrayDeque<Runnable>()
    private val served = HashMap<URI, ByteArray>()
    private val requested = mutableListOf<ModelFile>()
    private var closed = false

    /** Like the real downloader: the file lands in the folder only with its size and checksum. */
    private var work: (ModelFile, Path, (Double) -> Unit, () -> Boolean) -> Unit = { file, folder, progress, _ ->
        val bytes = served.getValue(file.url)
        progress(0.5)
        if (bytes.size.toLong() != file.bytes || sha(bytes) != file.sha256) {
            throw ModelDownloadException("Download of ${file.path} failed: checksum mismatch")
        }
        folder.resolve(file.path).writeBytes(bytes)
    }
    private val downloader =
        object : ModelDownloader() {
            override fun download(
                files: List<ModelFile>,
                folder: Path,
                progress: (Double) -> Unit,
                cancelled: () -> Boolean,
            ) {
                requested += files
                files.forEach { work(it, folder, progress, cancelled) }
            }

            override fun close() {
                closed = true
            }
        }
    private val smoked = mutableListOf<Path>()
    private var smoke: (Path) -> String? = { null }

    private fun newInstaller() =
        ToolInstaller(
            root,
            {
                assertTrue(Files.isExecutable(it), "the smoke test gets an executable")
                smoked.add(it)
                smoke(it)
            },
            { release, percent -> "Installing ty ${release.version} ${percent?.let { "$it %" } ?: "(unpacking)"}" },
            downloader,
            { queued += it },
            { it.run() },
        )

    private val installer by lazy { newInstaller() }

    private fun runQueued() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun release(
        version: String,
        archive: ByteArray =
            gzip(
                TarBuilder(gnu = true)
                    .dir("ty-aarch64/")
                    .file("ty-aarch64/ty", "#!/bin/sh\necho ty $version\n".toByteArray(), "755".toInt(8))
                    .bytes(),
            ),
        sha256: String = sha(archive),
    ): ToolRelease {
        val url = URI("https://example.com/download/$version/ty-aarch64.tar.gz")
        served[url] = archive
        return ToolRelease(version, url, archive.size.toLong(), sha256, ArchiveFormat.TAR_GZ, "ty-aarch64/ty")
    }

    private fun install(
        version: String,
        tasks: BackgroundTasks? = null,
    ) {
        installer.install(release(version), tasks)
        runQueued()
    }

    private fun entries() = root.listDirectoryEntries().map { it.name }.sorted()

    private fun tool(version: String) = InstalledTool(version, root.resolve("$version/ty-aarch64/ty").toAbsolutePath())

    @Test
    fun `an install downloads, unpacks, checks and switches to the release`() {
        val log = mutableListOf<String>()
        val states = mutableListOf<InstallState>()
        assertEquals(InstallState.NotInstalled, installer.state.get())
        installer.state.addListener { _, _, state -> states += state }

        val release = release("0.0.1")
        installer.install(release, BackgroundTasks.forTests { log += it })
        runQueued()

        assertEquals(tool("0.0.1"), installer.current.get())
        assertNull(installer.previous.get())
        assertEquals(
            listOf(
                InstallState.Downloading(0.0),
                InstallState.Downloading(0.5),
                InstallState.Downloading(-1.0),
                InstallState.Installed,
            ),
            states,
        )
        assertEquals(listOf(ModelFile("ty-aarch64.tar.gz", release.url, release.bytes, release.sha256)), requested)
        val checked = smoked.single()
        val staging = root.toAbsolutePath().resolve(".install-").toString()
        assertTrue(checked.toString().startsWith(staging), "in the staging folder: $checked")
        assertTrue(checked.endsWith("ty-aarch64/ty"), checked.toString())
        assertEquals(listOf("0.0.1", "current"), entries(), "the staging folder is gone")
        assertEquals("0.0.1", root.resolve("current").readText())
        val marker = Properties().apply { load(root.resolve("0.0.1/tool.properties").readText().reader()) }
        assertEquals("0.0.1", marker.getProperty("version"))
        assertEquals("ty-aarch64/ty", marker.getProperty("executable"))
        assertTrue(log.any { it.endsWith("started: Installing ty 0.0.1 0 %") }, log.toString())
        assertTrue(
            log.any { it.endsWith("Installing ty 0.0.1 50 % → Installing ty 0.0.1 (unpacking)") },
            log.toString(),
        )
        assertTrue(log.any { it.contains("finished in") }, log.toString())
        assumeTrue(posix, "POSIX modes")
        assertEquals(mode755, modeOf(tool("0.0.1").executable))
    }

    @Test
    fun `a second install keeps the first as previous, a third deletes the oldest`() {
        install("0.0.1")
        install("0.0.2")
        assertEquals(tool("0.0.2"), installer.current.get())
        assertEquals(tool("0.0.1"), installer.previous.get())

        install("0.0.3")

        assertEquals(tool("0.0.3"), installer.current.get())
        assertEquals(tool("0.0.2"), installer.previous.get())
        assertEquals(listOf("0.0.2", "0.0.3", "current", "previous"), entries())
    }

    @Test
    fun `installing the current version again keeps the previous one`() {
        install("0.0.1")
        install("0.0.2")

        install("0.0.2")

        assertEquals("0.0.2~2", root.resolve("current").readText())
        val reinstalled = root.resolve("0.0.2~2/ty-aarch64/ty").toAbsolutePath()
        assertEquals(InstalledTool("0.0.2", reinstalled), installer.current.get())
        assertEquals(tool("0.0.1"), installer.previous.get())
        assertEquals(listOf("0.0.1", "0.0.2~2", "current", "previous"), entries())
    }

    @Test
    fun `a rollback swaps the current and the previous version`() {
        install("0.0.1")
        install("0.0.2")

        installer.rollback()
        runQueued()

        assertEquals(tool("0.0.1"), installer.current.get())
        assertEquals(tool("0.0.2"), installer.previous.get())
        assertEquals(InstallState.Installed, installer.state.get())
        assertEquals(listOf("0.0.1", "0.0.2", "current", "previous"), entries())

        installer.rollback()
        runQueued()
        assertEquals(tool("0.0.2"), installer.current.get())
    }

    @Test
    fun `a rollback without a previous version does nothing`() {
        install("0.0.1")
        installer.rollback()
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `a failed smoke test switches nothing and removes what the install wrote`() {
        install("0.0.1")
        smoke = { "exit code 1" }
        val log = mutableListOf<String>()

        install("0.0.2", BackgroundTasks.forTests { log += it })

        assertEquals(InstallState.Failed("Version 0.0.2 does not work: exit code 1"), installer.state.get())
        assertEquals(tool("0.0.1"), installer.current.get())
        assertNull(installer.previous.get())
        assertEquals(listOf("0.0.1", "current"), entries())
        assertTrue(log.any { it.contains("failed after") && it.contains("does not work") }, log.toString())
    }

    @Test
    fun `a checksum mismatch fails and leaves the installed versions as they were`() {
        install("0.0.1")
        install("0.0.2")

        installer.install(release("0.0.3", sha256 = "0".repeat(64)), null)
        runQueued()

        val state = assertIs<InstallState.Failed>(installer.state.get())
        assertEquals("Download of ty-aarch64.tar.gz failed: checksum mismatch", state.message)
        assertEquals(tool("0.0.2"), installer.current.get())
        assertEquals(tool("0.0.1"), installer.previous.get())
        assertEquals(listOf("0.0.1", "0.0.2", "current", "previous"), entries())
    }

    @Test
    fun `an archive that is refused fails with the reason and leaves nothing behind`() {
        install("0.0.1")
        val evil = gzip(TarBuilder().file("ty-aarch64/ty", ByteArray(1)).file("../x", ByteArray(1)).bytes())

        installer.install(release("0.0.2", evil), null)
        runQueued()

        assertEquals(
            InstallState.Failed("Cannot unpack ty-aarch64.tar.gz: the archive has the unsafe path ../x"),
            installer.state.get(),
        )
        assertEquals(listOf("0.0.1", "current"), entries())
        assertTrue(!dir.resolve("tools/x").exists())
    }

    @Test
    fun `an archive with a marker of its own, or an executable named like it, is refused`() {
        install("0.0.1")
        val script = "#!/bin/sh\n".toByteArray()
        val marked =
            TarBuilder()
                .file("ty-aarch64/ty", script, "755".toInt(8))
                .file("tool.properties", "version=0.0.2\nexecutable=ty-aarch64/ty\n".toByteArray())
                .bytes()
        val plainUrl = URI("https://example.com/download/0.0.3/tool.properties")
        served[plainUrl] = script
        val plain =
            ToolRelease("0.0.3", plainUrl, script.size.toLong(), sha(script), ArchiveFormat.PLAIN, "tool.properties")

        for (release in listOf(release("0.0.2", gzip(marked)), plain)) {
            installer.install(release, null)
            runQueued()

            val state = assertIs<InstallState.Failed>(installer.state.get())
            assertTrue(state.message.contains("has a tool.properties at its top"), state.message)
            assertEquals(tool("0.0.1"), installer.current.get())
            assertEquals(listOf("0.0.1", "current"), entries())
        }
        assertTrue(smoked.size == 1, "only the first install was checked")
    }

    @Test
    fun `a cancel during the smoke test switches nothing`() {
        install("0.0.1")
        smoke = {
            installer.cancel()
            null
        }

        install("0.0.2")

        assertEquals(InstallState.Installed, installer.state.get())
        assertEquals(tool("0.0.1"), installer.current.get())
        assertNull(installer.previous.get())
        assertEquals(listOf("0.0.1", "current"), entries())
    }

    @Test
    fun `a cancel during the download ends the row as cancelled and leaves the installed version`() {
        install("0.0.1")
        val log = mutableListOf<String>()
        work = { _, _, progress, cancelled ->
            progress(0.3)
            installer.cancel()
            if (cancelled()) throw CancellationException("Download cancelled")
        }

        install("0.0.2", BackgroundTasks.forTests { log += it })

        assertEquals(InstallState.Installed, installer.state.get())
        assertEquals(tool("0.0.1"), installer.current.get())
        assertEquals(listOf("0.0.1", "current"), entries())
        assertTrue(log.any { it.contains("cancelled after") }, log.toString())
    }

    @ParameterizedTest
    @CsvSource(
        "../x, 64, 10, ty, ../x is not a valid version",
        "current, 64, 10, ty, current is not a valid version",
        "1.0, 12, 10, ty, The checksum of version 1.0 is not a SHA-256 value",
        "1.0, 64, 0, ty, The download of version 1.0 is too large",
        "1.0, 64, 10, ../ty, ../ty is not a path inside the archive",
    )
    fun `a release with values that cannot be installed fails before the download`(
        version: String,
        shaLength: Int,
        bytes: Long,
        executable: String,
        message: String,
    ) {
        val url = URI("https://example.com/ty")
        val release = ToolRelease(version, url, bytes, "a".repeat(shaLength), ArchiveFormat.PLAIN, executable)

        installer.install(release, null)
        runQueued()

        assertEquals(InstallState.Failed(message), installer.state.get())
        assertTrue(requested.isEmpty())
        assertTrue(!root.exists())
    }

    @Test
    fun `no second install starts while one runs`() {
        installer.install(release("0.0.1"), null)
        installer.install(release("0.0.2"), null)
        installer.deleteAll()
        assertEquals(1, queued.size)
    }

    @Test
    fun `a new installer on the same root reads the current and previous version from disk`() {
        install("0.0.1")
        install("0.0.2")

        val restarted = newInstaller()

        assertEquals(tool("0.0.2"), restarted.current.get())
        assertEquals(tool("0.0.1"), restarted.previous.get())
        assertEquals(InstallState.Installed, restarted.state.get())
    }

    @ParameterizedTest
    @ValueSource(strings = ["0.0.2", "0.0.2/tool.properties", "0.0.2/ty-aarch64/ty"])
    fun `a pointer to a missing folder, an unmarked one or a lost executable counts as not installed`(lost: String) {
        install("0.0.1")
        install("0.0.2")

        root.resolve(lost).toFile().deleteRecursively()
        val restarted = newInstaller()

        assertNull(restarted.current.get())
        assertEquals(InstallState.NotInstalled, restarted.state.get())
        assertEquals(tool("0.0.1"), restarted.previous.get())
    }

    @Test
    fun `an executable that lost its executable bit counts as not installed`() {
        assumeTrue(posix, "POSIX modes")
        install("0.0.1")
        Files.setPosixFilePermissions(tool("0.0.1").executable, PosixFilePermissions.fromString("rw-r--r--"))
        assertNull(newInstaller().current.get())
    }

    @Test
    fun `deleting removes every version and the pointers`() {
        install("0.0.1")
        install("0.0.2")

        installer.deleteAll()
        runQueued()

        assertTrue(!root.exists())
        assertNull(installer.current.get())
        assertNull(installer.previous.get())
        assertEquals(InstallState.NotInstalled, installer.state.get())
    }

    @Test
    fun `closing cancels a running install and closes the downloader`() {
        work = { _, _, _, cancelled -> if (cancelled()) throw CancellationException() }
        installer.install(release("0.0.1"), null)
        installer.close()
        runQueued()

        assertTrue(closed)
        assertEquals(InstallState.NotInstalled, installer.state.get())
        assertTrue(smoked.isEmpty())
    }
}
