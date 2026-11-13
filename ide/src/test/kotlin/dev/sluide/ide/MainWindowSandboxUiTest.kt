package dev.sluide.ide

import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** UI tests of the main window's Docker Sandbox menu. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowSandboxUiTest : MainWindowUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    private fun sessionItem(robot: FxRobot) = sandboxItems(robot)[0]

    private fun consoleItem(robot: FxRobot) = sandboxItems(robot)[1]

    @Test
    fun `offers the session item with an accelerator, disabled without a folder, and named by the folder`(
        robot: FxRobot,
    ) {
        val item = sessionItem(robot)

        assertEquals("New Docker Sandbox Session (Claude Code)", item.text)
        assertEquals(
            KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
            item.accelerator,
        )
        assertTrue(item.isDisable)

        val folder = home.resolve("work/project").createDirectories()
        robot.interact { projectService.open(folder) }
        assertEquals("New Docker Sandbox Session (Claude Code)", item.text)
        assertFalse(item.isDisable)

        folder.resolve("sbxenv.yaml").createFile()
        robot.interact { menu(robot, "Docker Sandbox").onShowing.handle(null) }

        assertEquals("New Docker Sandbox Session (Environment)", item.text)
        assertFalse(item.isDisable)
    }

    @Test
    fun `offers the Management Console second, without an accelerator and enabled without a folder`(robot: FxRobot) {
        val item = consoleItem(robot)

        assertEquals(2, sandboxItems(robot).size)
        assertEquals("Management Console", item.text)
        assertNull(item.accelerator)
        assertFalse(item.isDisable)
        assertTrue(sessionItem(robot).isDisable, "the session item still needs a folder")
    }

    @Test
    fun `firing the sandbox item chooses the action again from the current folder`(robot: FxRobot) {
        val folder = home.resolve("work/project").createDirectories()
        val file = folder.resolve("sbxenv.yaml").createFile()
        robot.interact { projectService.open(folder) }
        robot.interact { menu(robot, "Docker Sandbox").onShowing.handle(null) }
        assertEquals("New Docker Sandbox Session (Environment)", sessionItem(robot).text)

        // The menu is not shown again, so its label is stale when the accelerator fires.
        file.deleteExisting()
        robot.interact { sessionItem(robot).fire() }

        // The fake shell may already have exited, which appends " (exited)" to the title.
        assertTrue(tabTitles(robot).single().startsWith("Claude Code 1"), tabTitles(robot).toString())
    }

    @Test
    fun `starting a sandbox where it may not run shows an error and opens no tab`(robot: FxRobot) {
        val errors = mutableListOf<String>()
        mainWindow.showError = { _, message -> errors += message }
        robot.interact { projectService.open(home) }

        // Fired directly: the menu would not offer it, the start is checked again.
        robot.interact { sessionItem(robot).fire() }

        assertEquals(1, errors.size)
        assertTrue(errors.single().startsWith("Claude Code cannot be started. Docker sandboxes run only"), errors[0])
        assertEquals(emptyList(), tabTitles(robot))
        assertEquals(0, terminals.openCount)
    }

    @Test
    fun `the Docker Sandbox menu and its items are disabled without sbx, and until sbx has been found`(robot: FxRobot) {
        assertFalse(menu(robot, "Docker Sandbox").isDisable)

        val missing = otherWindowSandboxMenu(robot, CompletableFuture.completedFuture(false))
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(missing.isDisable)
        assertTrue(missing.items.all { it.isDisable })

        val sbxInstalled = CompletableFuture<Boolean>()
        val menu = otherWindowSandboxMenu(robot, sbxInstalled)
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(menu.isDisable)
        assertTrue(menu.items.all { it.isDisable })

        sbxInstalled.complete(true)
        WaitForAsyncUtils.waitForFxEvents()

        assertFalse(menu.isDisable)
        assertTrue(menu.items.none { it.isDisable })
    }

    @Test
    fun `the Management Console keeps one SBX tab, selects it while running, reopens once closed, replaces once ended`(
        robot: FxRobot,
    ) {
        // A stub sbx that runs until the file "stop" exists, found through the .profile of the test home folder.
        val bin = home.resolve("bin").createDirectories()
        val stop = home.resolve("stop")
        bin.resolve("sbx").apply {
            writeText("#!/bin/sh\nwhile [ ! -e '$stop' ]; do sleep 0.1; done\n")
            toFile().setExecutable(true)
        }
        home.resolve(".profile").writeText("PATH=\"$bin:\$PATH\"\nexport PATH\n")
        shellEnvironment += mapOf("HOME" to home.toString(), "SHELL" to "/bin/sh")

        // The stub's exit changes tabs and titles on the FX thread, so everything is read there.
        fun <T> onFx(read: () -> T): T {
            var value: T? = null
            robot.interact { value = read() }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }

        robot.interact { consoleItem(robot).fire() }
        val console = checkNotNull(onFx { editorArea.selectedTab })
        assertEquals(listOf("SBX"), onFx { editorArea.tabs.map { it.title.value } })
        assertTrue(console.tooltip.endsWith("$home: sbx"), console.tooltip)

        robot.interact { menu(robot, "Terminal").items.single().fire() }
        assertNotSame(console, onFx { editorArea.selectedTab })
        robot.interact { consoleItem(robot).fire() }

        assertEquals(2, onFx { editorArea.tabs.size })
        assertSame(console, onFx { editorArea.selectedTab })

        // Closed by the user while it runs: the item opens a new one instead of selecting the closed tab.
        robot.interact { editorArea.close(console) }
        robot.interact { consoleItem(robot).fire() }

        val second = checkNotNull(onFx { editorArea.selectedTab })
        assertNotSame(console, second)
        assertEquals("SBX", onFx { second.title.value })
        assertEquals(2, onFx { editorArea.tabs.size })

        stop.createFile()
        eventually(robot) { assertTrue(terminals.hasEnded(second)) }
        robot.interact { consoleItem(robot).fire() }

        val fresh = checkNotNull(onFx { editorArea.selectedTab })
        assertNotSame(second, fresh)
        assertEquals(1, onFx { editorArea.tabs.count { it.title.value.startsWith("SBX") } })
        assertEquals(2, onFx { editorArea.tabs.size })
        assertFalse(onFx { editorArea.tabs.contains(second) })
    }
}
