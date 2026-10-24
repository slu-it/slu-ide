package dev.sluide.httpclient.envedit

import javafx.animation.Animation
import javafx.event.ActionEvent
import javafx.scene.control.Button
import javafx.stage.Stage
import javafx.util.Duration
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How the environment files' view keeps the form in step with both texts: typing, the other file's tab and disk,
 * and texts that changed behind the form's back. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class EnvironmentFileViewSyncUiTest : EnvironmentFileViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    private fun EnvironmentFileView.lock(
        robot: FxRobot,
        path: ValuePath,
        env: String,
    ) = cell(robot, path, env).lookup(".env-lock") as Button

    @Test
    fun `typing restarts the pause, and the form reads the text once it ends`(robot: FxRobot) {
        val view = open(robot, publicText, privateText)
        val area = robot.lookup(".code-area").queryAs(CodeArea::class.java)
        val before = view.refreshes
        val start = publicText.indexOf("https://dev") + "https://dev".length

        var restartedAt = Double.MAX_VALUE
        var running = false
        var refreshesWhileTyping = -1
        robot.interact {
            area.insertText(start, "1")
            view.reparse.jumpTo(Duration.millis(200.0))
            area.insertText(start + 1, "2")
            restartedAt = view.reparse.currentTime.toMillis()
            running = view.reparse.status == Animation.Status.RUNNING
            refreshesWhileTyping = view.refreshes - before
            // Ends the pause at once, as its end would.
            view.reparse.stop()
            view.reparse.onFinished.handle(ActionEvent())
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(restartedAt < 50, "the pause went on at $restartedAt ms")
        assertTrue(running)
        assertEquals(0, refreshesWhileTyping)
        assertEquals(before + 1, view.refreshes)
        assertEquals("https://dev12", view.shown(robot, path("host"), "dev"))
    }

    @Test
    fun `a form edit while typing pauses keeps what was typed`(robot: FxRobot) {
        val view = open(robot, publicText, privateText)
        val lock = view.lock(robot, path("host"), "prod")
        val typed = publicText.replace("https://dev\"", "https://typed\"")

        robot.interact {
            view.replaceText(typed)
            lock.fire()
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(typed, view.text)
        assertEquals(emptyList(), host.edited)
        assertEquals("http-client.env.json changed meanwhile; nothing was changed, try again", view.warning.text)
        assertEquals("https://typed", view.shown(robot, path("host"), "dev"))
    }

    @Test
    fun `nothing changes when the other file's tab changed since the form read it`(robot: FxRobot) {
        val view = open(robot, publicText, privateText)
        val changed = """{"dev": {"token": "changed"}}"""
        host.tabs[privateFile] = changed

        view.type(robot, path("token"), "dev", "n3w")

        assertEquals(changed, tab(privateFile))
        assertEquals(publicText, view.text)
        assertTrue("changed meanwhile" in view.warning.text, view.warning.text)
        robot.interact { view.showValues.isSelected = true }
        assertEquals("changed", view.shown(robot, path("token"), "dev"))
    }

    @Test
    fun `a private file with a byte order mark is shown and moved without a warning`(robot: FxRobot) {
        val view = open(robot, publicText, "\uFEFF" + privateText)
        robot.interact { view.showValues.isSelected = true }
        assertEquals("s3cret", view.shown(robot, path("token"), "dev"))

        robot.interact { view.lock(robot, path("host"), "dev").fire() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(privateFile), host.edited)
        assertEquals("", view.warning.text)
    }

    @Test
    fun `a byte order mark in the editor and in the other file's tab does not matter`(robot: FxRobot) {
        host.tabs[privateFile] = "\uFEFF" + privateText
        val view = open(robot, "\uFEFF" + publicText, privateText)
        robot.interact { view.showValues.isSelected = true }
        assertTrue(view.form.editable, view.warning.text)
        assertEquals("s3cret", view.shown(robot, path("token"), "dev"))

        robot.interact { view.lock(robot, path("token"), "dev").fire() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(privateFile), host.edited)
        assertTrue("s3cret" in view.text, view.text)
        assertEquals("", view.warning.text)
    }

    @Test
    fun `an unreadable other file makes the form read-only`(robot: FxRobot) {
        Files.createDirectory(privateFile)
        val view = open(robot, publicText)

        assertFalse(view.form.editable)
        assertTrue(view.warning.text.startsWith("http-client.private.env.json cannot be read"), view.warning.text)
        assertTrue(view.lock(robot, path("host"), "dev").isDisable)
    }

    @Test
    fun `reads the other file again when the view is shown`(robot: FxRobot) {
        val view = open(robot, publicText, privateText)
        robot.interact { view.showValues.isSelected = true }
        host.tabs[privateFile] = """{"dev": {"token": "from the tab"}}"""

        assertEquals("s3cret", view.shown(robot, path("token"), "dev"))
        robot.interact { view.focus() }

        assertEquals("from the tab", view.shown(robot, path("token"), "dev"))
    }
}
