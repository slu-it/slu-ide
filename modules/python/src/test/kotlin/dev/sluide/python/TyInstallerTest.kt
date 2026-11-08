package dev.sluide.python

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TyInstallerTest {
    @TempDir
    lateinit var dir: Path

    private val fakes by lazy { TyFakes(dir) }

    private fun executableFile(name: String = "my-ty"): Path {
        val file = dir.resolve(name)
        file.writeText("#!/bin/sh\n")
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "POSIX permissions")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        return file
    }

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
        assertEquals(expected, TyInstaller.availabilityOf(custom, customProblem, state, current, pinnedBytes), case)
    }

    @Test
    fun `a custom path must be an absolute path of an executable file`() {
        val executable = executableFile()
        val plain = dir.resolve("plain").createFile()

        assertNull(TyInstaller.customPathProblem(""))
        assertNull(TyInstaller.customPathProblem(executable.toString()))
        assertEquals("$plain is not an executable file", TyInstaller.customPathProblem(plain.toString()))
        assertEquals("$dir is not an executable file", TyInstaller.customPathProblem(dir.toString()))
        assertEquals("my-ty is not an executable file", TyInstaller.customPathProblem("my-ty"))
    }

    @Test
    fun `an install of the pinned version makes ty available, runs it as a server and tells the listeners`() {
        val installer = fakes.installer()
        val availabilities = mutableListOf<ServerAvailability>()
        installer.availability.subscribe { it -> availabilities += it }
        var changes = 0
        installer.onCommandChanged { changes++ }
        assertNull(installer.command())

        installer.install(null)
        fakes.runQueued()

        val executable = fakes.root.resolve("0.0.99/ty-aarch64-unknown-linux-musl/ty").toAbsolutePath()
        assertEquals(ServerCommand(executable, listOf("server")), installer.command())
        assertEquals(InstalledTool("0.0.99", executable), installer.installed.get())
        assertTrue(installer.isPinned(installer.installed.get()))
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
    fun `an installed version is read from disk when the installer is created`() {
        fakes.installer().install(null)
        fakes.runQueued()

        val again = fakes.installer()

        assertEquals(
            "0.0.99",
            again
                .command()
                ?.executable
                ?.parent
                ?.parent
                ?.fileName
                ?.toString(),
        )
        assertEquals(ServerAvailability.Ready, again.availability.value)
    }

    @Test
    fun `update, rollback and delete change the command and tell the listeners each time`() {
        val installer = fakes.installer()
        installer.install(null)
        fakes.runQueued()
        val commands = mutableListOf<String?>()
        installer.onCommandChanged {
            commands +=
                installer
                    .command()
                    ?.executable
                    ?.parent
                    ?.parent
                    ?.fileName
                    ?.toString()
        }

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
        val installer = fakes.installer()
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
        val installer = fakes.installer()
        installer.install(null)
        fakes.runQueued()
        var changes = 0
        val subscription = installer.onCommandChanged { changes++ }
        val custom = executableFile()

        fakes.settings.apply(custom.toString())
        assertEquals(ServerCommand(custom, listOf("server")), installer.command())
        assertEquals(ServerAvailability.Ready, installer.availability.value)
        assertEquals(1, changes)

        // While the custom path is set, an update does not change the command.
        installer.update(fakes.release("0.1.0"), null)
        fakes.runQueued()
        assertEquals(1, changes)

        fakes.settings.apply("")
        assertEquals(
            "0.1.0",
            installer
                .command()
                ?.executable
                ?.parent
                ?.parent
                ?.fileName
                ?.toString(),
        )
        assertEquals(2, changes)

        subscription.close()
        fakes.settings.apply(custom.toString())
        assertEquals(2, changes, "a closed subscription is not told")
    }

    @Test
    fun `a custom path that is not executable gives no command and fails the availability`() {
        val installer = fakes.installer()
        installer.install(null)
        fakes.runQueued()
        val plain = dir.resolve("plain").createFile()

        fakes.settings.apply(plain.toString())

        assertNull(installer.command())
        assertEquals(
            ServerAvailability.Failed("$plain is not an executable file", canRetry = false),
            installer.availability.value,
        )
    }

    @Test
    fun `a custom file that stops being executable gives no command at once and fails the availability on a recheck`() {
        val installer = fakes.installer()
        val custom = executableFile()
        fakes.settings.apply(custom.toString())
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
    fun `without a build for the platform nothing is installed and the availability says why`() {
        val installer = fakes.installer(platform = null)

        installer.install(null)

        assertEquals(false, fakes.hasQueuedWork)
        assertEquals(false, installer.platformSupported)
        assertNull(installer.pinnedDownloadBytes)
        assertEquals(ServerAvailability.Failed(NO_TY_BUILD, canRetry = false), installer.availability.value)
    }

    @Test
    fun `a listener that throws does not keep the others from being told`() {
        val installer = fakes.installer()
        var told = false
        installer.onCommandChanged { error("broken") }
        installer.onCommandChanged { told = true }

        installer.install(null)
        fakes.runQueued()

        assertTrue(told)
    }

    @Test
    fun `the task row names the version and the download's percent`() {
        assertEquals("Downloading ty 0.1.0 40 %", TyInstaller.taskName(fakes.release("0.1.0"), 40))
        assertEquals("Installing ty 0.1.0", TyInstaller.taskName(fakes.release("0.1.0"), null))
    }

    companion object {
        private val tool = InstalledTool("0.0.99", Path.of("/x/ty"))

        @JvmStatic
        fun availabilities(): List<Arguments> =
            listOf(
                Arguments.of(
                    "custom usable",
                    "/a/ty",
                    null,
                    InstallState.NotInstalled,
                    null,
                    1L,
                    ServerAvailability.Ready,
                ),
                Arguments.of(
                    "custom unusable, also with an installed version",
                    "/a/ty",
                    "/a/ty is not an executable file",
                    InstallState.Installed,
                    tool,
                    1L,
                    ServerAvailability.Failed("/a/ty is not an executable file", canRetry = false),
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
                    ServerAvailability.Failed(NO_TY_BUILD, canRetry = false),
                ),
            )
    }
}
