package dev.sluide.java

import dev.sluide.backgroundtasks.BackgroundTasks
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** jdtls's installer: the shared install rules are tested in server-tools; here what Lombok and the memory add. */
class JdtlsInstallerTest {
    @TempDir
    lateinit var dir: Path

    private val fakes by lazy { JdtlsFakes(dir) }
    private val installer by lazy { fakes.installer() }

    @Test
    fun `an install checks jdtls between two clean-ups of the smoke folder`() {
        val planted = fakes.smokeRoot.resolve("config/config.ini")
        Files.createDirectories(planted.parent)
        Files.writeString(planted, "osgi.framework=file:/evil.jar")
        var plantedSeen: Boolean? = null
        fakes.smoke = {
            plantedSeen = Files.exists(planted)
            Files.createDirectories(fakes.smokeRoot.resolve("data"))
            null
        }

        installer.install(null)
        fakes.runQueued()

        assertEquals("1.63.0", installer.installed.get()?.version)
        assertEquals(false, plantedSeen, "what was planted is gone before the smoke test")
        assertFalse(Files.exists(fakes.smokeRoot), "and what the run left is gone after it")
    }

    @Test
    fun `the installer names itself for the install banner`() {
        assertEquals("the Java language server (jdtls)", installer.toolName)
    }

    @Test
    fun `Lombok is installed after jdtls, from the pin, and lombokJar names it`() {
        assertNull(installer.lombokJar())

        installer.install(null)
        assertTrue(fakes.lombokDownloads.isEmpty() && !fakes.hasQueuedLombokWork, "not before jdtls is installed")
        fakes.runQueued()

        assertEquals("1.63.0", installer.installed.get()?.version)
        assertEquals(listOf(fakes.lombokPin.url), fakes.lombokDownloads.map { it.url })
        val jar = assertNotNull(installer.lombokJar())
        assertEquals(fakes.lombokRoot.resolve("1.18.48/lombok-1.18.48.jar").toString(), jar.toString())
        assertEquals(FAKE_LOMBOK_BYTES, Files.size(jar))
        assertNull(installer.statusNote.value)
    }

    @Test
    fun `a Lombok failure leaves jdtls installed and shows in the status note`() {
        fakes.lombokFailure = "Cannot download lombok-1.18.48.jar: offline"

        installer.install(null)
        fakes.runQueued()

        assertEquals("1.63.0", installer.installed.get()?.version)
        assertNull(installer.lombokJar())
        assertEquals(
            "Lombok could not be installed: Cannot download lombok-1.18.48.jar: offline",
            installer.statusNote.value,
        )
    }

    @Test
    fun `the next update tries Lombok again and clears the note when it works`() {
        fakes.lombokFailure = "offline"
        installer.install(null)
        fakes.runQueued()
        fakes.lombokFailure = null

        installer.update(fakes.server.release("1.64.0"), null)
        fakes.runQueued()

        assertEquals("1.64.0", installer.installed.get()?.version)
        assertNotNull(installer.lombokJar())
        assertNull(installer.statusNote.value)
    }

    @Test
    fun `an update does not download the pinned Lombok again`() {
        installer.install(null)
        fakes.runQueued()

        installer.update(fakes.server.release("1.64.0"), null)
        fakes.runQueued()

        assertEquals(1, fakes.lombokDownloads.size)
    }

    @Test
    fun `no Lombok is installed when jdtls does not work`() {
        fakes.smoke = { "no initialize answer" }

        installer.install(null)
        fakes.runQueued()

        assertNull(installer.installed.get())
        assertTrue(fakes.lombokDownloads.isEmpty())
        assertNull(installer.lombokJar())
        assertNull(installer.statusNote.value)
    }

    @Test
    fun `no Lombok is installed when the install is cancelled`() {
        installer.install(null)
        installer.cancel()
        fakes.runQueued()

        assertNull(installer.installed.get())
        assertTrue(fakes.lombokDownloads.isEmpty())
    }

    @Test
    fun `a cancelled update does not try Lombok again`() {
        fakes.lombokFailure = "offline"
        installer.install(null)
        fakes.runQueued()
        fakes.lombokFailure = null

        installer.update(fakes.server.release("1.64.0"), null)
        installer.cancel()
        fakes.runQueued()

        assertEquals("1.63.0", installer.installed.get()?.version)
        assertEquals(1, fakes.lombokDownloads.size, "only the failed first try")
        assertNull(installer.lombokJar())
    }

    @Test
    fun `a rollback does not try Lombok`() {
        fakes.lombokFailure = "offline"
        installer.install(null)
        fakes.runQueued()
        installer.update(fakes.server.release("1.64.0"), null)
        fakes.runQueued()
        assertEquals(2, fakes.lombokDownloads.size, "the install and the update tried")

        installer.rollback()
        fakes.runQueued()

        assertEquals("1.63.0", installer.installed.get()?.version)
        assertEquals(2, fakes.lombokDownloads.size)
    }

    @Test
    fun `a rollback after a failed update does not try Lombok`() {
        installer.install(null)
        fakes.runQueued()
        installer.update(fakes.server.release("1.64.0"), null)
        fakes.runQueued()
        deleteTree(fakes.lombokRoot)
        fakes.smoke = { "no initialize answer" }
        installer.update(fakes.server.release("1.65.0"), null)
        fakes.runQueued()
        fakes.smoke = { null }
        assertEquals(1, fakes.lombokDownloads.size)

        installer.rollback()
        fakes.runQueued()

        assertEquals("1.63.0", installer.installed.get()?.version)
        assertEquals(1, fakes.lombokDownloads.size)
    }

    @Test
    fun `a Lombok jar that has gone from disk is no jar`() {
        installer.install(null)
        fakes.runQueued()

        Files.delete(checkNotNull(installer.lombokJar()))

        assertNull(installer.lombokJar())
    }

    @Test
    fun `an update installs the pinned Lombok over an older one`() {
        val older = JdtlsFakes(dir, lombokVersion = "1.18.40")
        older.installer().install(null)
        older.runQueued()

        installer.update(fakes.server.release("1.64.0"), null)
        fakes.runQueued()

        assertEquals("lombok-1.18.48.jar", installer.lombokJar()?.fileName.toString())
        assertEquals(1, fakes.lombokDownloads.size)
    }

    @Test
    fun `an update installs Lombok again when its files have gone`() {
        installer.install(null)
        fakes.runQueued()
        deleteTree(fakes.lombokRoot)

        installer.update(fakes.server.release("1.64.0"), null)
        fakes.runQueued()

        assertEquals(2, fakes.lombokDownloads.size)
        assertNotNull(installer.lombokJar())
        assertNull(installer.statusNote.value)
    }

    @Test
    fun `Lombok's download gets a task row of its own when the install has tasks`() {
        val log = mutableListOf<String>()

        installer.install(BackgroundTasks.forTests { log += it })
        fakes.runQueued()

        assertTrue(log.any { "started: Downloading Lombok 1.18.48 0 %" in it }, log.toString())
    }

    @Test
    fun `Delete while Lombok is being installed leaves neither, also not an older Lombok`() {
        val older = JdtlsFakes(dir, lombokVersion = "1.18.40")
        older.installer().install(null)
        older.runQueued()
        installer.update(fakes.server.release("1.64.0"), null)
        fakes.server.runQueued()
        assertTrue(fakes.hasQueuedLombokWork, "Lombok waits for its turn")

        installer.deleteAll()
        fakes.runQueued()

        assertNull(installer.installed.get())
        assertNull(installer.lombokJar())
        assertFalse(Files.exists(fakes.lombokRoot.resolve("1.18.40")))
        assertFalse(Files.exists(fakes.lombokRoot.resolve("1.18.48")))

        installer.install(null)
        fakes.runQueued()

        assertNotNull(installer.lombokJar(), "the wish to delete ended with the delete")
    }

    @Test
    fun `Install installs only Lombok when jdtls is installed and Lombok is not`() {
        fakes.lombokFailure = "offline"
        installer.install(null)
        fakes.runQueued()
        assertTrue(installer.needsInstall())
        fakes.lombokFailure = null

        installer.install(null)
        fakes.runQueued()

        assertEquals(1, fakes.server.downloaded.size, "jdtls is not downloaded again")
        assertEquals(2, fakes.lombokDownloads.size)
        assertNotNull(installer.lombokJar())
        assertFalse(installer.needsInstall())
        assertNull(installer.statusNote.value)
    }

    @Test
    fun `a restarted app still says that Lombok is not installed and installs only Lombok`() {
        fakes.lombokFailure = "offline"
        installer.install(null)
        fakes.runQueued()

        val later = JdtlsFakes(dir)
        val restarted = later.installer()

        assertEquals("Lombok is not installed", restarted.statusNote.value)
        assertTrue(restarted.needsInstall())
        restarted.install(null)
        later.runQueued()
        assertTrue(later.server.downloaded.isEmpty(), "jdtls is not downloaded again")
        assertNotNull(restarted.lombokJar())
        assertNull(restarted.statusNote.value)
    }

    @Test
    fun `deleting jdtls takes the note about Lombok away`() {
        fakes.lombokFailure = "offline"
        installer.install(null)
        fakes.runQueued()
        val later = JdtlsFakes(dir)
        val restarted = later.installer()
        assertEquals("Lombok is not installed", restarted.statusNote.value)

        restarted.deleteAll()
        later.runQueued()

        assertNull(restarted.installed.get())
        assertNull(restarted.statusNote.value)
    }

    @Test
    fun `a newer Thoreau with another Lombok pin needs an install`() {
        val older = JdtlsFakes(dir, lombokVersion = "1.18.40")
        older.installer().install(null)
        older.runQueued()

        assertTrue(installer.needsInstall())
        installer.install(null)
        fakes.runQueued()

        assertEquals("lombok-1.18.48.jar", installer.lombokJar()?.fileName.toString())
        assertFalse(installer.needsInstall())
    }

    @Test
    fun `nothing needs an install while Lombok is being installed or when it is there`() {
        installer.install(null)
        fakes.server.runQueued()
        assertTrue(fakes.hasQueuedLombokWork)
        assertFalse(installer.needsInstall(), "the Lombok install runs")
        assertEquals("Installing Lombok…", installer.statusNote.value)

        fakes.runQueued()
        assertFalse(installer.needsInstall())
    }

    @Test
    fun `Install over a jdtls older than the pin downloads the pin and one newer than the pin is kept`() {
        installer.update(fakes.server.release("1.62.0"), null)
        fakes.runQueued()
        deleteTree(fakes.lombokRoot)
        val older = JdtlsFakes(dir)
        val olderInstaller = older.installer()

        olderInstaller.install(null)
        older.runQueued()

        assertEquals(1, older.server.downloaded.size, "the pin is downloaded")
        assertEquals("1.63.0", olderInstaller.installed.get()?.version)
        assertNotNull(olderInstaller.lombokJar())

        olderInstaller.update(older.server.release("1.64.0"), null)
        older.runQueued()
        deleteTree(older.lombokRoot)
        val newer = JdtlsFakes(dir)
        val newerInstaller = newer.installer()

        newerInstaller.install(null)
        newer.runQueued()

        assertTrue(newer.server.downloaded.isEmpty(), "the newer version stays")
        assertEquals("1.64.0", newerInstaller.installed.get()?.version)
        assertNotNull(newerInstaller.lombokJar())
    }

    @Test
    fun `the executable is the installed one or the custom one that is valid`() {
        assertNull(installer.executable())

        installer.install(null)
        fakes.runQueued()
        val installed = checkNotNull(installer.executable())
        assertTrue(installed.endsWith("bin/jdtls") && Files.isExecutable(installed), installed.toString())

        val custom = dir.resolve("custom/bin/jdtls")
        Files.createDirectories(custom.parent)
        Files.createFile(custom)
        custom.toFile().setExecutable(true)
        fakes.settings.apply(custom.toString())
        assertEquals(custom, installer.executable())

        fakes.settings.apply(dir.resolve("missing").toString())
        assertNull(installer.executable(), "a custom path that is no executable file is no jdtls")

        fakes.settings.apply("")
        assertEquals(installed, installer.executable())
    }

    @Test
    fun `changes of the path and the memory inside one batch tell the listeners once`() {
        var calls = 0
        installer.onCommandChanged { calls++ }

        val result =
            installer.batchCommandChanges {
                fakes.settings.apply("/opt/jdtls/bin/jdtls")
                fakes.settings.applyMaxMemory(4096)
                "done"
            }

        assertEquals("done", result)
        assertEquals(1, calls)

        installer.batchCommandChanges { }
        assertEquals(1, calls, "a batch without a change tells nobody")

        fakes.settings.applyMaxMemory(1024)
        fakes.settings.apply("")
        assertEquals(3, calls, "outside a batch each change tells")
    }

    @Test
    fun `a batch inside a batch tells once, when the outer one ends`() {
        var calls = 0
        installer.onCommandChanged { calls++ }

        installer.batchCommandChanges {
            installer.batchCommandChanges { fakes.settings.applyMaxMemory(4096) }
            assertEquals(0, calls, "the inner batch does not tell")
            fakes.settings.applyMaxMemory(1024)
        }

        assertEquals(1, calls)
    }

    @Test
    fun `a batch that fails still tells the listeners of what it changed`() {
        var calls = 0
        installer.onCommandChanged { calls++ }

        runCatching {
            installer.batchCommandChanges {
                fakes.settings.applyMaxMemory(4096)
                error("the next store fails")
            }
        }

        assertEquals(1, calls)
    }

    private fun deleteTree(folder: Path) {
        Files.walk(folder).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `Delete removes jdtls and Lombok`() {
        fakes.lombokFailure = "offline"
        installer.install(null)
        fakes.runQueued()
        assertEquals("Lombok could not be installed: offline", installer.statusNote.value)
        fakes.lombokFailure = null
        installer.install(null)
        fakes.runQueued()
        assertTrue(Files.isDirectory(fakes.lombokRoot.resolve("1.18.48")))

        installer.deleteAll()
        fakes.runQueued()

        assertNull(installer.installed.get())
        assertNull(installer.lombokJar())
        assertFalse(Files.exists(fakes.lombokRoot.resolve("1.18.48")))
        assertFalse(Files.exists(fakes.tool.root.resolve("1.63.0")))
        assertNull(installer.statusNote.value)
    }

    @Test
    fun `Delete after a Lombok failure clears the failure note`() {
        fakes.lombokFailure = "offline"
        installer.install(null)
        fakes.runQueued()
        assertEquals("Lombok could not be installed: offline", installer.statusNote.value)

        installer.deleteAll()
        fakes.runQueued()

        assertNull(installer.statusNote.value)
    }

    @Test
    fun `a Lombok jar installed by an earlier run is found when the installer is created`() {
        installer.install(null)
        fakes.runQueued()

        val later = JdtlsFakes(dir).installer()

        assertEquals(installer.lombokJar(), later.lombokJar())
        assertNotNull(later.lombokJar())
    }

    @Test
    fun `command listeners fire when the maximum memory or the custom path changes, until closed`() {
        var calls = 0
        val subscription = installer.onCommandChanged { calls++ }
        assertEquals(0, calls, "not when it is registered")

        fakes.settings.applyMaxMemory(4096)
        assertEquals(1, calls)
        fakes.settings.applyMaxMemory(4096)
        assertEquals(1, calls, "the same value is no change")

        fakes.settings.apply("/opt/jdtls/bin/jdtls")
        assertEquals(2, calls)

        subscription.close()
        fakes.settings.applyMaxMemory(1024)
        fakes.settings.apply("")
        assertEquals(2, calls)
    }
}
