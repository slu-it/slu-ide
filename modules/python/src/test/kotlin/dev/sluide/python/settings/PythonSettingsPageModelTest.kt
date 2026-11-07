package dev.sluide.python.settings

import dev.sluide.localmodels.InstallState
import dev.sluide.localmodels.InstalledTool
import dev.sluide.python.FAKE_ASSET
import dev.sluide.python.HttpAnswer
import dev.sluide.python.NO_TY_BUILD
import dev.sluide.python.TyFakes
import dev.sluide.python.TyUpdateCheck
import dev.sluide.python.TyUpdates
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

class PythonSettingsPageModelTest {
    @TempDir
    lateinit var dir: Path

    private val fakes by lazy { TyFakes(dir) }
    private val installer by lazy { fakes.installer() }
    private val questions = mutableListOf<String>()
    private var deleteAnswer = false
    private var chosen: Path? = null
    private val opened = mutableListOf<URI>()

    /** The latest release GitHub answers: 0.1.0 with the fake platform's asset. */
    private var latestVersion = "0.1.0"
    private val checks = mutableListOf<() -> Unit>()

    private val updates by lazy {
        TyUpdates(fakes.pin, "linux-aarch64", {
            HttpAnswer(200, emptyMap(), latestJson())
        }, { checks += it::run }, { it.run() })
    }

    private val dialogs =
        object : PythonSettingsDialogs {
            override fun confirmDelete(question: String): Boolean {
                questions += question
                return deleteAnswer
            }

            override fun chooseExecutable(current: String): Path? = chosen
        }

    private val model by lazy { PythonSettingsPageModel(fakes.settings, installer, updates, dialogs) { opened += it } }

    private fun latestJson() =
        """
        {"tag_name": "$latestVersion", "html_url": "https://github.com/astral-sh/ty/releases/tag/$latestVersion",
         "prerelease": false, "assets": [{"name": "$FAKE_ASSET", "size": 1000, "digest": "sha256:${"c".repeat(64)}",
         "browser_download_url": "https://github.com/astral-sh/ty/releases/download/$latestVersion/$FAKE_ASSET"}]}
        """.trimIndent()

    private fun runChecks() {
        while (checks.isNotEmpty()) checks.removeFirst()()
    }

    private fun installPinned() {
        model.installOrCancel()
        fakes.runQueued()
    }

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
        assertEquals("Installed: ty 0.0.99", controls.status)
        assertTrue(controls.installDisabled)
        assertFalse(controls.deleteDisabled)
    }

    @Test
    fun `status texts`() {
        val tool = InstalledTool("0.1.0", dir)
        assertEquals(
            "Downloading 42 %",
            PythonSettingsPageModel.statusText(InstallState.Downloading(0.42), null, "0.0.99", 1),
        )
        assertEquals(
            "Unpacking and checking ty…",
            PythonSettingsPageModel.statusText(InstallState.Downloading(-1.0), null, "0.0.99", 1),
        )
        assertEquals(
            "Failed: no network",
            PythonSettingsPageModel.statusText(InstallState.Failed("no network"), tool, "0.0.99", 1),
        )
        assertEquals(
            "Installed: ty 0.1.0 (this Thoreau was tested with 0.0.99)",
            PythonSettingsPageModel.statusText(InstallState.Installed, tool, "0.0.99", 1),
        )
        assertEquals(NO_TY_BUILD, PythonSettingsPageModel.statusText(InstallState.NotInstalled, null, "0.0.99", null))
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
        assertEquals("ty 0.1.0 is available, newer than tested with this Thoreau.", controls.result)
        assertTrue(controls.releaseNotesShown)
        assertTrue(controls.updateShown)
        model.openReleaseNotes()
        assertEquals(listOf(URI("https://github.com/astral-sh/ty/releases/tag/0.1.0")), opened)

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
        assertEquals("ty 0.0.99 is up to date.", controls.result)
        assertFalse(controls.updateShown)
        assertFalse(controls.releaseNotesShown)
    }

    @Test
    fun `result texts`() {
        assertEquals(
            "ty 0.1.0 is available, but not for this platform.",
            PythonSettingsPageModel.resultText(TyUpdateCheck.NoBuild("0.1.0", null)),
        )
        assertEquals(
            "Cannot check for updates: GitHub answered with HTTP status 502",
            PythonSettingsPageModel.resultText(TyUpdateCheck.Failed("GitHub answered with HTTP status 502")),
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
    fun `Delete asks first, naming the versions and the folder, and Cancel keeps them`() {
        installPinned()
        installer.update(fakes.release("0.1.0"), null)
        fakes.runQueued()

        model.delete()
        fakes.runQueued()

        assertEquals(
            listOf(
                "Delete ty 0.1.0 and ty 0.0.99? The files in ${fakes.root} are removed permanently; Python code " +
                    "intelligence then needs ty to be installed again.",
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
        val custom = dir.resolve("my-ty")
        custom.writeText("#!/bin/sh\n")
        Files.setPosixFilePermissions(custom, PosixFilePermissions.fromString("rwxr-xr-x"))
        chosen = custom

        model.browse()
        assertTrue(model.useCustom.get())
        assertEquals(custom.toString(), model.customPath.get())
        assertEquals("", fakes.settings.tyPath.get(), "not applied yet")

        model.apply()
        assertEquals(custom.toString(), fakes.settings.tyPath.get())

        model.useCustom.set(false)
        model.load()
        assertTrue(model.useCustom.get(), "loading drops the edit")

        model.useCustom.set(false)
        model.apply()
        assertEquals("", fakes.settings.tyPath.get())
        assertEquals("", model.customPath.get())
    }

    @Test
    fun `the hint says what is wrong with a chosen custom path`() {
        assertEquals("", model.customHint(), "the download is chosen")

        model.useCustom.set(true)
        assertEquals("Enter the absolute path of a ty executable.", model.customHint())

        model.customPath.set("ty")
        assertEquals("Use an absolute path.", model.customHint())

        model.customPath.set(dir.resolve("missing").toString())
        assertEquals("${dir.resolve("missing")} is not an executable file", model.customHint())
    }

    @Test
    fun `an installed version older than the pin offers the pin, which installs it and keeps the old one`() {
        installer.update(fakes.release("0.0.98"), null)
        fakes.runQueued()
        assertEquals("Install 0.0.99 (tested with this Thoreau)", model.controls().pinText)
        assertEquals("Installed: ty 0.0.98 (this Thoreau was tested with 0.0.99)", model.controls().status)

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
        assertEquals("Install 0.0.99 (tested with this Thoreau)", PythonSettingsPageModel.pinOffer("", older, "0.0.99"))
        assertNull(PythonSettingsPageModel.pinOffer("/opt/ty", older, "0.0.99"), "a custom path")
        assertNull(PythonSettingsPageModel.pinOffer("", null, "0.0.99"), "nothing installed: Install")
        assertNull(PythonSettingsPageModel.pinOffer("", InstalledTool("0.0.99", dir), "0.0.99"), "the pin")
        assertNull(PythonSettingsPageModel.pinOffer("", InstalledTool("0.1.0", dir), "0.0.99"), "updated beyond it")
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
