package dev.sluide.servertools

import dev.sluide.languageservers.ServerAvailability
import dev.sluide.languageservers.ServerCommand
import dev.sluide.localmodels.InstallState
import dev.sluide.localmodels.InstalledTool
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerToolInstallerTest {
    @TempDir
    lateinit var dir: Path

    private val setting by lazy { acmeSetting(dir) }
    private val fakes by lazy { ServerToolFakes(acmeTool(dir)) }

    private fun defaultInstaller() = ServerToolInstaller(fakes.tool, setting, fakes.tools())

    private fun executableFile(name: String = "my-acme-ls"): Path {
        val file = dir.resolve(name)
        file.writeText("#!/bin/sh\n")
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "POSIX permissions")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        return file
    }

    private fun versionOf(command: ServerCommand?) =
        command
            ?.executable
            ?.parent
            ?.parent
            ?.fileName
            ?.toString()

    @ParameterizedTest(name = "{0}")
    @MethodSource("availabilities")
    fun `availability follows the custom path, then the installed version, then the download`(
        case: String,
        custom: String,
        customProblem: String?,
        state: InstallState,
        current: InstalledTool?,
        pinnedBytes: Long?,
        expected: ServerAvailability,
    ) {
        assertEquals(
            expected,
            ServerToolInstaller.availabilityOf(custom, customProblem, state, current, pinnedBytes, NO_BUILD),
            case,
        )
    }

    @Test
    fun `a custom path must be an absolute path of an executable file`() {
        val executable = executableFile()
        val plain = dir.resolve("plain").createFile()

        assertNull(ServerToolInstaller.customPathProblem(""))
        assertNull(ServerToolInstaller.customPathProblem(executable.toString()))
        assertEquals("$plain is not an executable file", ServerToolInstaller.customPathProblem(plain.toString()))
        assertEquals("$dir is not an executable file", ServerToolInstaller.customPathProblem(dir.toString()))
        assertEquals("my-acme-ls is not an executable file", ServerToolInstaller.customPathProblem("my-acme-ls"))
    }

    @Test
    fun `an install of the pinned version makes the tool available, runs it as a server and tells the listeners`() {
        val installer = defaultInstaller()
        val availabilities = mutableListOf<ServerAvailability>()
        installer.availability.subscribe { it -> availabilities += it }
        var changes = 0
        installer.onCommandChanged { changes++ }
        assertNull(installer.command())

        installer.install(null)
        fakes.runQueued()

        val executable =
            fakes.tool.root
                .resolve("0.0.99/acme-ls-aarch64-linux/acme-ls")
                .toAbsolutePath()
        assertEquals(ServerCommand(executable, listOf("serve", "--stdio")), installer.command())
        assertEquals(InstalledTool("0.0.99", executable), installer.installed.get())
        assertTrue(installer.isPinned(installer.installed.get()))
        assertEquals("acme-ls", installer.toolName)
        assertEquals(1, changes)
        assertEquals(
            listOf(
                ServerAvailability.Missing(13_659_057),
                ServerAvailability.Installing(0.0),
                ServerAvailability.Installing(0.5),
                ServerAvailability.Installing(-1.0),
                ServerAvailability.Ready,
            ),
            availabilities,
        )
        assertEquals(listOf(FAKE_ASSET), fakes.downloaded.map { it.path })
    }

    @Test
    fun `whoever sees the tool become ready gets its command at once`() {
        // Code intelligence starts the server as soon as the availability turns ready; that start asks for the
        // command right away, so the command must not lag behind the availability (the real app found none).
        val installer = defaultInstaller()
        val commandsWhenReady = mutableListOf<ServerCommand?>()
        installer.availability.subscribe { now ->
            if (now == ServerAvailability.Ready) commandsWhenReady += installer.command()
        }

        installer.install(null)
        fakes.runQueued()

        assertEquals(1, commandsWhenReady.size)
        assertNotNull(commandsWhenReady.single())
        assertEquals(installer.command(), commandsWhenReady.single())
    }

    @Test
    fun `an installed version is read from disk when the installer is created`() {
        defaultInstaller().install(null)
        fakes.runQueued()

        val again = defaultInstaller()

        assertEquals("0.0.99", versionOf(again.command()))
        assertEquals(ServerAvailability.Ready, again.availability.value)
    }

    @Test
    fun `update, rollback and delete change the command and tell the listeners each time`() {
        val installer = defaultInstaller()
        installer.install(null)
        fakes.runQueued()
        val commands = mutableListOf<String?>()
        installer.onCommandChanged { commands += versionOf(installer.command()) }

        installer.update(fakes.release("0.1.0"), null)
        fakes.runQueued()
        assertEquals("0.0.99", installer.previous.get()?.version)
        assertEquals(false, installer.isPinned(installer.installed.get()))
        installer.rollback()
        fakes.runQueued()
        installer.deleteAll()
        fakes.runQueued()

        assertEquals(listOf("0.1.0", "0.0.99", null), commands)
        assertEquals(ServerAvailability.Missing(13_659_057), installer.availability.value)
    }

    @Test
    fun `a failed update keeps the installed version ready and the command unchanged`() {
        val installer = defaultInstaller()
        installer.install(null)
        fakes.runQueued()
        var changes = 0
        installer.onCommandChanged { changes++ }
        fakes.smoke = { "it crashed" }

        installer.update(fakes.release("0.1.0"), null)
        fakes.runQueued()

        assertEquals(InstallState.Failed("Version 0.1.0 does not work: it crashed"), installer.state.get())
        assertEquals(ServerAvailability.Ready, installer.availability.value)
        assertEquals(0, changes)
    }

    @Test
    fun `a custom path wins over the download, and setting or clearing it tells the listeners`() {
        val installer = defaultInstaller()
        installer.install(null)
        fakes.runQueued()
        var changes = 0
        val subscription = installer.onCommandChanged { changes++ }
        val custom = executableFile()

        setting.apply(custom.toString())
        assertEquals(ServerCommand(custom, listOf("serve", "--stdio")), installer.command())
        assertEquals(ServerAvailability.Ready, installer.availability.value)
        assertEquals(1, changes)

        // While the custom path is set, an update does not change the command.
        installer.update(fakes.release("0.1.0"), null)
        fakes.runQueued()
        assertEquals(1, changes)

        setting.apply("")
        assertEquals("0.1.0", versionOf(installer.command()))
        assertEquals(2, changes)

        subscription.close()
        setting.apply(custom.toString())
        assertEquals(2, changes, "a closed subscription is not told")
    }

    @Test
    fun `a custom path that is not executable gives no command and fails the availability`() {
        val installer = defaultInstaller()
        installer.install(null)
        fakes.runQueued()
        val plain = dir.resolve("plain").createFile()

        setting.apply(plain.toString())

        assertNull(installer.command())
        assertEquals(
            ServerAvailability.Failed("$plain is not an executable file", canRetry = false),
            installer.availability.value,
        )
    }

    @Test
    fun `a custom file that stops being executable gives no command at once and fails the availability on a recheck`() {
        val installer = defaultInstaller()
        val custom = executableFile()
        setting.apply(custom.toString())
        val availabilities = mutableListOf<ServerAvailability>()
        installer.availability.subscribe { it -> availabilities += it }

        Files.setPosixFilePermissions(custom, PosixFilePermissions.fromString("rw-r--r--"))
        assertNull(installer.command())
        installer.recheckCustomPath()

        assertEquals(
            listOf(
                ServerAvailability.Ready,
                ServerAvailability.Failed("$custom is not an executable file", canRetry = false),
            ),
            availabilities,
        )
    }

    @Test
    fun `without a build for the platform nothing is installed and the availability names the tool`() {
        val noBuild = ServerToolFakes(acmeTool(dir, platform = null))
        val installer = ServerToolInstaller(noBuild.tool, setting, noBuild.tools())

        installer.install(null)

        assertEquals(false, noBuild.hasQueuedWork)
        assertEquals(false, installer.platformSupported)
        assertNull(installer.pinnedDownloadBytes)
        assertEquals(ServerAvailability.Failed(NO_BUILD, canRetry = false), installer.availability.value)
    }

    @Test
    fun `a listener that throws does not keep the others from being told`() {
        val installer = defaultInstaller()
        var told = false
        installer.onCommandChanged { error("broken") }
        installer.onCommandChanged { told = true }

        installer.install(null)
        fakes.runQueued()

        assertTrue(told)
    }

    @Test
    fun `the task row names the tool, the version and the download's percent`() {
        assertEquals("Downloading acme-ls 0.1.0 40 %", fakes.tool.taskName(fakes.release("0.1.0"), 40))
        assertEquals("Installing acme-ls 0.1.0", fakes.tool.taskName(fakes.release("0.1.0"), null))
    }

    companion object {
        private const val NO_BUILD = "No acme-ls build for this platform"

        private val tool = InstalledTool("0.0.99", Path.of("/x/acme-ls"))

        @JvmStatic
        fun availabilities(): List<Arguments> =
            listOf(
                Arguments.of(
                    "custom usable",
                    "/a/acme-ls",
                    null,
                    InstallState.NotInstalled,
                    null,
                    1L,
                    ServerAvailability.Ready,
                ),
                Arguments.of(
                    "custom unusable, also with an installed version",
                    "/a/acme-ls",
                    "/a/acme-ls is not an executable file",
                    InstallState.Installed,
                    tool,
                    1L,
                    ServerAvailability.Failed("/a/acme-ls is not an executable file", canRetry = false),
                ),
                Arguments.of("installed", "", null, InstallState.Installed, tool, 1L, ServerAvailability.Ready),
                Arguments.of("updating", "", null, InstallState.Downloading(0.3), tool, 1L, ServerAvailability.Ready),
                Arguments.of("update failed", "", null, InstallState.Failed("x"), tool, 1L, ServerAvailability.Ready),
                Arguments.of(
                    "downloading",
                    "",
                    null,
                    InstallState.Downloading(0.3),
                    null,
                    1L,
                    ServerAvailability.Installing(0.3),
                ),
                Arguments.of(
                    "unpacking",
                    "",
                    null,
                    InstallState.Downloading(-1.0),
                    null,
                    1L,
                    ServerAvailability.Installing(-1.0),
                ),
                Arguments.of("failed", "", null, InstallState.Failed("x"), null, 1L, ServerAvailability.Failed("x")),
                Arguments.of("missing", "", null, InstallState.NotInstalled, null, 42L, ServerAvailability.Missing(42)),
                Arguments.of(
                    "no build",
                    "",
                    null,
                    InstallState.NotInstalled,
                    null,
                    null,
                    ServerAvailability.Failed(NO_BUILD, canRetry = false),
                ),
            )
    }
}
