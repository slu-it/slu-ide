package dev.sluide.servertools

import dev.sluide.localmodels.InstallState
import dev.sluide.localmodels.InstalledTool
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerToolPageModelTest {
    @TempDir
    lateinit var dir: Path

    private val fakes by lazy { ServerToolFakes(acmeTool(dir)) }
    private val setting by lazy { acmeSetting(dir) }
    private val installer by lazy { ServerToolInstaller(fakes.tool, setting, fakes.tools()) }
    private val questions = mutableListOf<String>()
    private var deleteAnswer = false
    private var chosen: Path? = null
    private val opened = mutableListOf<URI>()

    /** The latest release the source answers: 0.1.0 with the fake platform's asset. */
    private var latestVersion = "0.1.0"
    private val checks = mutableListOf<() -> Unit>()

    private val updates by lazy {
        ToolUpdates(fakes.tool, FakeSource { acmeRelease(latestVersion) }, { checks += it::run }, { it.run() })
    }

    private val dialogs =
        object : ServerToolDialogs {
            override fun confirmDelete(question: String): Boolean {
                questions += question
                return deleteAnswer
            }

            override fun chooseExecutable(current: String): Path? = chosen
        }

    private val model by lazy { ServerToolPageModel("Acme", setting, installer, updates, dialogs) { opened += it } }

    private fun runChecks() {
        while (checks.isNotEmpty()) checks.removeFirst()()
    }

    private fun installPinned() {
        model.installOrCancel()
        fakes.runQueued()
    }

    private fun status(
        state: InstallState,
        installed: InstalledTool?,
        pinnedBytes: Long? = 1,
    ) = ServerToolPageModel.statusText(fakes.tool, state, installed, "0.0.99", pinnedBytes)

    @Test
    fun `before an install the page offers Install with the download size and nothing else`() {
        val controls = model.controls()

        assertEquals("Not installed (14 MB download)", controls.status)
        assertEquals("Install", controls.installText)
        assertFalse(controls.installDisabled)
        assertNull(controls.result)
        assertFalse(controls.updateShown)
        assertNull(controls.rollbackText)
        assertTrue(controls.deleteDisabled)
    }

    @Test
    fun `Install installs the pinned version, the button cancels while it runs`() {
        model.installOrCancel()
        assertEquals("Cancel", model.controls().installText)
        assertEquals("Downloading 0 %", model.controls().status)

        model.installOrCancel()
        fakes.runQueued()

        assertNull(installer.installed.get(), "cancelled")
        assertEquals("Not installed (14 MB download)", model.controls().status)

        installPinned()

        val controls = model.controls()
        assertEquals("Installed: acme-ls 0.0.99", controls.status)
        assertTrue(controls.installDisabled)
        assertFalse(controls.deleteDisabled)
    }

    @Test
    fun `status texts`() {
        val tool = InstalledTool("0.1.0", dir)
        assertEquals("Downloading 42 %", status(InstallState.Downloading(0.42), null))
        assertEquals("Unpacking and checking acme-ls…", status(InstallState.Downloading(-1.0), null))
        assertEquals("Failed: no network", status(InstallState.Failed("no network"), tool))
        assertEquals(
            "Installed: acme-ls 0.1.0 (this Thoreau was tested with 0.0.99)",
            status(InstallState.Installed, tool),
        )
        assertEquals("No acme-ls build for this platform", status(InstallState.NotInstalled, null, null))
    }

    @Test
    fun `Check for Updates shows a newer version with its release notes, and Update installs it`() {
        installPinned()

        model.checkForUpdates()
        assertEquals("Checking for updates…", model.controls().result)
        assertTrue(model.controls().checkDisabled)
        model.checkForUpdates()
        assertEquals(1, checks.size, "one check at a time")
        runChecks()

        val controls = model.controls()
        assertEquals("acme-ls 0.1.0 is available, newer than tested with this Thoreau.", controls.result)
        assertTrue(controls.releaseNotesShown)
        assertTrue(controls.updateShown)
        model.openReleaseNotes()
        assertEquals(listOf(URI("https://example.com/acme/releases/0.1.0")), opened)

        model.update()
        fakes.runQueued()

        assertEquals("0.1.0", installer.installed.get()?.version)
        assertNull(model.updateCheck.get(), "the result is cleared once another version is installed")
        assertEquals("Roll Back to 0.0.99", model.controls().rollbackText)
    }

    @Test
    fun `an up-to-date check offers no update`() {
        installPinned()
        latestVersion = "0.0.99"

        model.checkForUpdates()
        runChecks()

        val controls = model.controls()
        assertEquals("acme-ls 0.0.99 is up to date.", controls.result)
        assertFalse(controls.updateShown)
        assertFalse(controls.releaseNotesShown)
    }

    @Test
    fun `result texts`() {
        assertEquals(
            "acme-ls 0.1.0 is available, but not for this platform.",
            ServerToolPageModel.resultText("acme-ls", UpdateCheck.NoBuild("0.1.0", null)),
        )
        assertEquals(
            "Cannot check for updates: Acme answered with HTTP status 502",
            ServerToolPageModel.resultText("acme-ls", UpdateCheck.Failed("Acme answered with HTTP status 502")),
        )
    }

    @Test
    fun `Roll Back goes to the previous version`() {
        installPinned()
        installer.update(fakes.release("0.1.0"), null)
        fakes.runQueued()

        model.rollback()
        fakes.runQueued()

        assertEquals("0.0.99", installer.installed.get()?.version)
        assertEquals("Roll Back to 0.1.0", model.controls().rollbackText)
    }

    @Test
    fun `Delete asks first, naming the versions, the folder and the language, and Cancel keeps them`() {
        installPinned()
        installer.update(fakes.release("0.1.0"), null)
        fakes.runQueued()

        model.delete()
        fakes.runQueued()

        assertEquals(
            listOf(
                "Delete acme-ls 0.1.0 and acme-ls 0.0.99? The files in ${fakes.tool.root} are removed permanently; " +
                    "Acme code intelligence then needs acme-ls to be installed again.",
            ),
            questions,
        )
        assertEquals("0.1.0", installer.installed.get()?.version)

        deleteAnswer = true
        model.delete()
        fakes.runQueued()

        assertNull(installer.installed.get())
        assertNull(installer.previous.get())
    }

    @Test
    fun `the custom path is a draft until applied, and choosing the download clears it`() {
        val custom = dir.resolve("my-acme-ls")
        custom.writeText("#!/bin/sh\n")
        Files.setPosixFilePermissions(custom, PosixFilePermissions.fromString("rwxr-xr-x"))
        chosen = custom

        model.browse()
        assertTrue(model.useCustom.get())
        assertEquals(custom.toString(), model.customPath.get())
        assertEquals("", setting.path.get(), "not applied yet")

        model.apply()
        assertEquals(custom.toString(), setting.path.get())

        model.useCustom.set(false)
        model.load()
        assertTrue(model.useCustom.get(), "loading drops the edit")

        model.useCustom.set(false)
        model.apply()
        assertEquals("", setting.path.get())
        assertEquals("", model.customPath.get())
    }

    @Test
    fun `the hint says what is wrong with a chosen custom path`() {
        assertEquals("", model.customHint(), "the download is chosen")

        model.useCustom.set(true)
        assertEquals("Enter the absolute path of a acme-ls executable.", model.customHint())

        model.customPath.set("acme-ls")
        assertEquals("Use an absolute path.", model.customHint())

        model.customPath.set(dir.resolve("missing").toString())
        assertEquals("${dir.resolve("missing")} is not an executable file", model.customHint())
    }

    @Test
    fun `an installed version older than the pin offers the pin, which installs it and keeps the old one`() {
        installer.update(fakes.release("0.0.98"), null)
        fakes.runQueued()
        assertEquals("Install 0.0.99 (tested with this Thoreau)", model.controls().pinText)
        assertEquals("Installed: acme-ls 0.0.98 (this Thoreau was tested with 0.0.99)", model.controls().status)

        model.installPinned()
        fakes.runQueued()

        assertEquals("0.0.99", installer.installed.get()?.version)
        assertEquals("0.0.98", installer.previous.get()?.version)
        assertNull(model.controls().pinText)
        assertEquals(2, fakes.downloaded.size)
    }

    @Test
    fun `the pin is offered only over an older download`() {
        val older = InstalledTool("0.0.98", dir)
        assertEquals("Install 0.0.99 (tested with this Thoreau)", ServerToolPageModel.pinOffer("", older, "0.0.99"))
        assertNull(ServerToolPageModel.pinOffer("/opt/acme-ls", older, "0.0.99"), "a custom path")
        assertNull(ServerToolPageModel.pinOffer("", null, "0.0.99"), "nothing installed: Install")
        assertNull(ServerToolPageModel.pinOffer("", InstalledTool("0.0.99", dir), "0.0.99"), "the pin")
        assertNull(ServerToolPageModel.pinOffer("", InstalledTool("0.1.0", dir), "0.0.99"), "updated beyond it")
    }

    @Test
    fun `the pin that is the previous version is switched back to without a download`() {
        installPinned()
        installer.update(fakes.release("0.0.98"), null)
        fakes.runQueued()

        model.installPinned()
        fakes.runQueued()

        assertEquals("0.0.99", installer.installed.get()?.version)
        assertEquals(2, fakes.downloaded.size, "only the two installs downloaded")
    }

    @Test
    fun `an update to the version rolled back from is switched back to without a download`() {
        installPinned()
        installer.update(fakes.release("0.1.0"), null)
        fakes.runQueued()
        model.rollback()
        fakes.runQueued()

        model.checkForUpdates()
        runChecks()
        model.update()
        fakes.runQueued()

        assertEquals("0.1.0", installer.installed.get()?.version)
        assertEquals(2, fakes.downloaded.size, "only the two installs downloaded")
    }
}
