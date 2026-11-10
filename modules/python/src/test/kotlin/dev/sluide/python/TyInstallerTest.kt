package dev.sluide.python

import dev.sluide.languageservers.ServerAvailability
import dev.sluide.languageservers.ServerCommand
import dev.sluide.localmodels.InstalledTool
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** What ty's installer adds to the shared one (`ServerToolInstallerTest` in server-tools): ty's names and command. */
class TyInstallerTest {
    @TempDir
    lateinit var dir: Path

    private val fakes by lazy { TyFakes(dir) }

    @Test
    fun `the pinned install runs ty server from the asset's folder under the ty root`() {
        val installer = fakes.installer()

        installer.install(null)
        fakes.runQueued()

        val executable = fakes.root.resolve("0.0.99/ty-aarch64-unknown-linux-musl/ty").toAbsolutePath()
        assertEquals(ServerCommand(executable, listOf("server")), installer.command())
        assertEquals(InstalledTool("0.0.99", executable), installer.installed.get())
        assertEquals("ty", installer.toolName)
        assertEquals(listOf(FAKE_ASSET), fakes.server.downloaded.map { it.path })
    }

    @Test
    fun `the task row and a missing build name ty`() {
        assertEquals("Downloading ty 0.1.0 40 %", fakes.tool.taskName(fakes.release("0.1.0"), 40))
        assertEquals("Installing ty 0.1.0", fakes.tool.taskName(fakes.release("0.1.0"), null))
        val noBuild = TyInstaller(fakes.settings, TyTool.tool(fakes.pin, null, fakes.root))
        assertEquals(
            ServerAvailability.Failed("No ty build for this platform", canRetry = false),
            noBuild.availability.value,
        )
    }

    @Test
    fun `ty is downloaded to the tools folder of Thoreau's home`() {
        assertEquals(
            Path.of(System.getProperty("user.home"), ".sluide", "tools", "ty"),
            TyTool.DEFAULT_ROOT,
        )
    }
}
