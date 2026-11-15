package dev.sluide.java

import dev.sluide.languageservers.ServerCommand
import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.localmodels.Checksum
import dev.sluide.servertools.ToolPin
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The pin resource of jdtls, how its release is unpacked and the launch function the installer and smoke test use. */
class JdtlsToolTest {
    @TempDir
    lateinit var dir: Path

    private val asset = "jdt-language-server-1.63.0-202610191322.tar.gz"

    @Test
    fun `the pin resource names the one archive for every Thoreau platform`() {
        val pin = JdtlsTool.pin()

        assertEquals("1.63.0", pin.version)
        for (platform in ToolPin.PLATFORMS) {
            val pinned = assertNotNull(pin.asset(platform), platform)
            assertEquals(asset, pinned.name)
            assertEquals(51_143_565L, pinned.bytes)
            assertEquals(
                Checksum.Sha256("b1f2abe0b3cccb462cf64fb4a5587c7d09df60cf8be7748b152cbd89d018bdc4"),
                pinned.checksum,
            )
            assertEquals(URI("https://download.eclipse.org/jdtls/milestones/1.63.0/$asset"), pinned.url)
        }
    }

    @Test
    fun `the pinned release is a tar gz that holds bin jdtls and has no version command`() {
        val tool = JdtlsTool.tool(platform = "linux-aarch64")
        val release = checkNotNull(tool.pinnedRelease())

        assertEquals(ArchiveFormat.TAR_GZ, release.format)
        assertEquals("bin/jdtls", release.executable)
        assertNull(tool.versionArguments)
        assertEquals("jdtls", tool.name)
    }

    @Test
    fun `the launch function is the real command on a throwaway configuration, so that the install stays unchanged`() {
        val home = dir.resolve("jdtls")
        home.resolve("bin").createDirectories()
        Files.createFile(home.resolve("bin/jdtls"))
        home.resolve("plugins").createDirectories()
        Files.createFile(home.resolve("plugins/org.eclipse.equinox.launcher_1.8.0.jar"))
        for (config in listOf("config_linux", "config_linux_arm", "config_mac", "config_mac_arm")) {
            home.resolve(config).createDirectories()
        }
        val smokeRoot = dir.resolve("smoke")
        val smokeData = smokeRoot.resolve("data")
        val smokeConfig = smokeRoot.resolve("config")
        var memory = 1024
        val tool =
            JdtlsTool.tool(
                platform = "linux-aarch64",
                maxMemoryMb = { memory },
                smokeRoot = smokeRoot,
            )
        val executable = home.resolve("bin/jdtls")

        val first: ServerCommand = checkNotNull(tool.command(executable))
        memory = 4096
        val second: ServerCommand = checkNotNull(tool.command(executable))

        val real = checkNotNull(JdtlsLaunch.command(home, smokeConfig, smokeData, 1024, null))
        val jar = real.arguments.indexOf("-jar")
        val installConfig = checkNotNull(JdtlsLaunch.configFolder(home))
        assertEquals(
            real.copy(
                arguments =
                    real.arguments.take(jar) +
                        listOf("-Dosgi.sharedConfiguration.area=$installConfig", "-Dosgi.checkConfiguration=true") +
                        real.arguments.drop(jar),
            ),
            first,
        )
        assertEquals("-Xmx4096m", second.arguments.single { it.startsWith("-Xmx") })
    }

    @Test
    fun `an archive of another version or with a path in its name is not downloaded from`() {
        assertEquals(URI("https://download.eclipse.org/jdtls/milestones/1.63.0/$asset"), milestoneUrl("1.63.0", asset))
        for (
        (version, name) in
        listOf(
            "1.63.0" to "../1.62.0/jdt-language-server-1.63.0-202610191322.tar.gz",
            "1.63.0" to "jdt-language-server-1.62.0-202610191322.tar.gz",
            "1.63.0" to "evil.tar.gz",
            "1.63/../0" to asset,
            "1.63.0" to "jdt-language-server-1.63.0-2026.tar.gz",
        )
        ) {
            assertFailsWith<IllegalArgumentException>("$version $name") { milestoneUrl(version, name) }
        }
        assertFailsWith<IllegalArgumentException> { JdtlsTool.downloadUrl("1.63.0", "linux-x64", "evil.tar.gz") }
    }

    @Test
    fun `the smoke folder is in the user's own home`() {
        assertEquals(
            Path.of(System.getProperty("user.home"), ".sluide", "jdtls", "smoke"),
            JdtlsTool.DEFAULT_SMOKE_ROOT,
        )
    }

    @Test
    fun `without a smoke root of its own the launch function uses that one`() {
        val home = dir.resolve("jdtls")
        home.resolve("bin").createDirectories()
        Files.createFile(home.resolve("bin/jdtls"))
        home.resolve("plugins").createDirectories()
        Files.createFile(home.resolve("plugins/org.eclipse.equinox.launcher_1.8.0.jar"))
        for (config in listOf("config_linux", "config_linux_arm", "config_mac", "config_mac_arm")) {
            home.resolve(config).createDirectories()
        }

        val arguments = checkNotNull(JdtlsTool.tool().command(home.resolve("bin/jdtls"))).arguments

        assertEquals(
            JdtlsTool.DEFAULT_SMOKE_ROOT.resolve("config").toString(),
            arguments[
                arguments.indexOf("-configuration") +
                    1,
            ],
        )
        assertEquals(JdtlsTool.DEFAULT_SMOKE_ROOT.resolve("data").toString(), arguments[arguments.indexOf("-data") + 1])
    }

    @Test
    fun `a check that cannot get the lock of the smoke folder waits, then gives up and touches nothing`() {
        val smokeRoot = dir.resolve("smoke")
        val other = smokeRoot.resolve("config/config.ini")
        other.parent.createDirectories()
        other.writeText("another Thoreau's")
        val lockFile = dir.resolve("smoke.lock")
        var ran = false
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val reason =
                    JdtlsTool.smokeCheck(smokeRoot, 300.milliseconds) {
                        ran = true
                        null
                    }(dir)

                assertEquals("another Thoreau is checking jdtls; try again in a moment", reason)
            }
        }

        assertFalse(ran)
        assertEquals("another Thoreau's", other.readText())

        assertNull(JdtlsTool.smokeCheck(smokeRoot, 300.milliseconds) { null }(dir), "the lock was released")
    }

    @Test
    fun `a check runs under the lock and releases it`() {
        val smokeRoot = dir.resolve("smoke")
        var heldWhileChecking = false
        JdtlsTool.smokeCheck(smokeRoot, 300.milliseconds) {
            // The same JVM cannot lock a file twice: the overlap shows that the check holds it.
            heldWhileChecking =
                runCatching {
                    FileChannel.open(dir.resolve("smoke.lock"), StandardOpenOption.WRITE).use { it.tryLock() }
                }.exceptionOrNull() is OverlappingFileLockException
            null
        }(dir)

        assertTrue(heldWhileChecking)
    }

    @Test
    fun `the lock file is created with its folder when the smoke folder's parent does not exist yet`() {
        val smokeRoot = dir.resolve("not/yet/smoke")

        assertNull(JdtlsTool.smokeCheck(smokeRoot, 300.milliseconds) { null }(dir))

        assertTrue(Files.exists(dir.resolve("not/yet/smoke.lock")))
    }

    @Test
    fun `a lock file that is a link is not followed`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "symbolic links")
        val target = dir.resolve("elsewhere").also { it.writeText("mine") }
        Files.createSymbolicLink(dir.resolve("smoke.lock"), target)
        var ran = false

        val reason =
            JdtlsTool.smokeCheck(dir.resolve("smoke"), 300.milliseconds) {
                ran = true
                null
            }(dir)

        assertFalse(ran)
        assertTrue(reason.orEmpty().startsWith("Cannot lock ${dir.resolve("smoke.lock")}: "), reason)
        assertEquals("mine", target.readText())
    }

    /** A smoke folder that cannot be emptied: a read-only `data` folder with a file (not for root, which can). */
    private fun undeletable(smokeRoot: Path): Path {
        val data = smokeRoot.resolve("data").createDirectories()
        data.resolve("x").writeText("x")
        data.toFile().setWritable(false)
        assumeTrue(
            runCatching { Files.delete(data.resolve("x")) }.isFailure,
            "this user can delete in read-only folders",
        )
        return data
    }

    @Test
    fun `a clean-up before the check that fails fails the check run and the check does not run`() {
        val smokeRoot = dir.resolve("smoke")
        val data = undeletable(smokeRoot)
        var ran = false
        try {
            assertFailsWith<java.io.IOException> {
                JdtlsTool.smokeCheck(smokeRoot, 300.milliseconds) {
                    ran = true
                    null
                }(dir)
            }
            assertFalse(ran)
        } finally {
            data.toFile().setWritable(true)
        }
    }

    @Test
    fun `a clean-up after a passed check that fails does not fail the install`() {
        val smokeRoot = dir.resolve("smoke")
        lateinit var data: Path
        try {
            val reason =
                JdtlsTool.smokeCheck(smokeRoot, 300.milliseconds) {
                    data = undeletable(smokeRoot)
                    null
                }(dir)

            assertNull(reason)
            assertTrue(Files.exists(data.resolve("x")), "left behind, and only logged")
        } finally {
            data.toFile().setWritable(true)
        }
    }

    @Test
    fun `the smoke folders are cleaned before the check runs, also of what was planted, and after it ends`() {
        val smokeRoot = dir.resolve("smoke")
        val planted = smokeRoot.resolve("config/config.ini")
        planted.parent.createDirectories()
        planted.writeText("osgi.framework=file:/evil.jar")
        var plantedWhenChecked: Boolean? = null
        val check =
            JdtlsTool.smokeCheck(smokeRoot) {
                plantedWhenChecked = Files.exists(planted) || Files.exists(smokeRoot.resolve("config"))
                smokeRoot
                    .resolve("config")
                    .createDirectories()
                    .resolve("org.eclipse.osgi")
                    .writeText("cache")
                null
            }

        assertNull(check(dir.resolve("bin/jdtls")))

        assertEquals(false, plantedWhenChecked)
        assertFalse(Files.exists(smokeRoot), "the cache does not stay")
    }

    @Test
    fun `the smoke folders are cleaned after a failing check and a throwing one`() {
        val smokeRoot = dir.resolve("smoke")

        fun leave() = smokeRoot.resolve("data").createDirectories()
        assertEquals("no answer", JdtlsTool.smokeCheck(smokeRoot) { leave().let { "no answer" } }(dir))
        assertFalse(Files.exists(smokeRoot))
        assertFailsWith<IllegalStateException> {
            JdtlsTool.smokeCheck(smokeRoot) { leave().let { error("boom") } }(dir)
        }
        assertFalse(Files.exists(smokeRoot))
    }

    @Test
    fun `a smoke folder that is a link is refused and neither the link nor its target is touched`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "symbolic links")
        val target = dir.resolve("elsewhere").createDirectories()
        target.resolve("keep.txt").writeText("mine")
        val smokeRoot = dir.resolve("smoke")
        Files.createSymbolicLink(smokeRoot, target)
        var ran = false

        val reason =
            JdtlsTool.smokeCheck(smokeRoot) {
                ran = true
                null
            }(dir)

        assertFalse(ran)
        assertEquals("$smokeRoot is a link; remove it so that jdtls can be checked", reason)
        assertTrue(Files.isSymbolicLink(smokeRoot))
        assertEquals("mine", target.resolve("keep.txt").readText())
    }

    @Test
    fun `a link inside the smoke folder is removed without following it`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "symbolic links")
        val target = dir.resolve("elsewhere").createDirectories()
        target.resolve("keep.txt").writeText("mine")
        val smokeRoot = dir.resolve("smoke").createDirectories()
        Files.createSymbolicLink(smokeRoot.resolve("config"), target)

        JdtlsTool.smokeCheck(smokeRoot) { null }(dir)

        assertFalse(Files.exists(smokeRoot, LinkOption.NOFOLLOW_LINKS))
        assertEquals("mine", target.resolve("keep.txt").readText())
    }

    @Test
    fun `the launch function is null for an executable that is not in an unpacked jdtls`() {
        val tool = JdtlsTool.tool(platform = "linux-aarch64")

        assertNull(tool.command(dir.resolve("bin/jdtls")))
    }
}
