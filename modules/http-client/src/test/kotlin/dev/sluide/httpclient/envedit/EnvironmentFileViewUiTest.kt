package dev.sluide.httpclient.envedit

import javafx.event.ActionEvent
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The environment files' view: both files in the form, editing values, modes, warnings. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class EnvironmentFileViewUiTest : EnvironmentFileViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    private fun FxRobot.area() = lookup(".code-area").queryAs(CodeArea::class.java)

    @Test
    fun `shows both files merged with the file of each value, private values masked until shown`(robot: FxRobot) {
        val view = open(robot, publicText, privateText)

        assertEquals(
            listOf("Name", "Type", "dev", "prod"),
            view.form.tree.columns
                .map { it.text },
        )
        assertEquals(
            listOf("host", "port", "debug", "token"),
            view.item(EnvEntry.Variables.key).children.map { (it.value as EnvEntry.Item).row.label },
        )
        assertEquals(
            FileSide.PRIVATE,
            view
                .row(path("token"))
                .cells
                .getValue("dev")
                .side,
        )
        assertEquals("https://dev", view.shown(robot, path("host"), "dev"))
        assertEquals("8080", view.shown(robot, path("port"), "dev"))
        assertEquals(EnvTexts.UNDEFINED, view.shown(robot, path("port"), "prod"))
        assertEquals(EnvTexts.MASK, view.shown(robot, path("token"), "dev"))
        val debug = (view.cell(robot, path("debug"), "dev").graphic as HBox).children.first()
        assertTrue(assertIs<CheckBox>(debug).isSelected)
        val lock = view.cell(robot, path("token"), "dev").lookup(".env-lock") as Button
        assertEquals("Private file", lock.tooltip.text)

        robot.interact { view.showValues.isSelected = true }
        assertEquals("s3cret", view.shown(robot, path("token"), "dev"))
    }

    @Test
    fun `shows the same form for the private file`(robot: FxRobot) {
        val view = open(robot, publicText, privateText, open = privateFile)

        assertEquals(FileSide.PRIVATE, view.side)
        assertEquals("https://prod", view.shown(robot, path("host"), "prod"))
        assertEquals(EnvTexts.MASK, view.shown(robot, path("token"), "dev"))
    }

    @Test
    fun `an edited value changes the source as one undoable change`(robot: FxRobot) {
        val view = open(robot, publicText, privateText)

        view.type(robot, path("host"), "dev", "https://dev2")

        assertEquals(publicText.replace("https://dev\"", "https://dev2\""), view.text)
        assertTrue(view.dirty.get())
        assertEquals(emptyList(), host.edited)
        assertEquals("https://dev2", view.shown(robot, path("host"), "dev"))
        robot.interact { robot.area().undo() }
        assertEquals(publicText, view.text)
    }

    @Test
    fun `numbers are only committed when they are numbers`(robot: FxRobot) {
        val view = open(robot, publicText, privateText)

        val cell = view.edit(robot, path("port"), "dev")
        val field = cell.graphic as TextField
        robot.interact {
            field.text = "80a"
            field.fireEvent(ActionEvent())
        }
        assertTrue("env-invalid" in field.styleClass)
        assertTrue(cell.isEditing)
        robot.interact {
            field.text = "1e3"
            field.fireEvent(ActionEvent())
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue("\"port\": 1e3," in view.text, view.text)
    }

    @Test
    fun `a private value changes the private file's tab, not the opened file`(robot: FxRobot) {
        val view = open(robot, publicText, privateText)

        view.type(robot, path("token"), "dev", "n3w")

        assertEquals(publicText, view.text)
        assertEquals(listOf(privateFile), host.edited)
        assertEquals(privateText.replace("s3cret", "n3w"), tab(privateFile))
        assertEquals(privateText, privateFile.readText())
    }

    @Test
    fun `the tab's unsaved text of the other file is the one shown and edited`(robot: FxRobot) {
        host.tabs[privateFile] = """{"dev": {"token": "unsaved"}}"""
        val view = open(robot, publicText, privateText)
        robot.interact { view.showValues.isSelected = true }

        assertEquals("unsaved", view.shown(robot, path("token"), "dev"))
    }

    @Test
    fun `the lock moves a value to the private file, which is created when missing`(robot: FxRobot) {
        val view = open(robot, publicText)
        val lock = view.cell(robot, path("host"), "dev").lookup(".env-lock") as Button
        assertEquals("Public file", lock.tooltip.text)

        robot.interact { lock.fire() }

        assertEquals("{}\n", privateFile.readText())
        assertTrue("\"host\": \"https://dev\"" in tab(privateFile).orEmpty(), tab(privateFile))
        assertFalse("https://dev\"" in view.text, view.text)
        assertEquals(
            FileSide.PRIVATE,
            view
                .row(path("host"))
                .cells
                .getValue("dev")
                .side,
        )
    }

    @Test
    fun `the lock moves a private value to the opened file`(robot: FxRobot) {
        val view = open(robot, publicText, privateText)

        robot.interact { (view.cell(robot, path("token"), "dev").lookup(".env-lock") as Button).fire() }

        assertTrue("\"token\": \"s3cret\"" in view.text, view.text)
        assertFalse("s3cret" in tab(privateFile).orEmpty())
    }

    @Test
    fun `invalid json makes the form read-only with the error shown`(robot: FxRobot) {
        val view = open(robot, "{ \"dev\": ", privateText)

        assertFalse(view.form.editable)
        assertTrue(view.warning.text.startsWith("http-client.env.json:"), view.warning.text)
        assertTrue(view.form.addVariableButton.isDisable)
        assertTrue(view.addEnvironment.isDisable)

        robot.interact { view.replaceText(publicText) }
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { view.form.editable }
        WaitForAsyncUtils.waitForFxEvents()
        assertFalse(view.warning.isVisible, view.warning.text)
        assertEquals("https://dev", view.shown(robot, path("host"), "dev"))
    }

    @Test
    fun `shows the source, both or the form, keeping the divider`(robot: FxRobot) {
        val view = open(robot, publicText, privateText)
        val (source, split, visual) = view.modeButtons

        assertEquals(EnvMode.SPLIT, view.mode.get())
        assertTrue(split.isSelected)
        assertEquals(2, view.split.items.size)
        robot.interact { view.split.setDividerPositions(0.3) }

        robot.interact { source.fire() }
        assertEquals(1, view.split.items.size)
        assertFalse(view.form.node in view.split.items)
        robot.interact { source.fire() }
        assertTrue(source.isSelected)
        robot.interact { visual.fire() }
        assertEquals(listOf(view.form.node), view.split.items.toList())
        robot.interact { split.fire() }
        assertEquals(2, view.split.items.size)
        assertEquals(0.3, view.split.dividerPositions[0], 0.02)
    }

    @Test
    fun `warns while git would commit the private file`(robot: FxRobot) {
        var committable = true
        session.wouldCommit = { it == privateFile.toAbsolutePath().normalize() && committable }
        val view = open(robot, publicText, privateText)

        assertEquals(
            "http-client.private.env.json is not ignored by git: its secrets could be committed",
            view.warning.text,
        )
        committable = false
        robot.interact { session.gitStatusChanged() }
        assertFalse(view.warning.isVisible)
        assertNull(view.warning.tooltip)
    }

    @Test
    fun `follows a rename between the two names and gives up on others`(robot: FxRobot) {
        val view = open(robot, publicText)
        val moved = dir.resolve("sub").apply { toFile().mkdir() }.resolve("http-client.private.env.json")
        publicFile.toFile().renameTo(moved.toFile())

        assertTrue(WaitForAsyncUtils.asyncFx<Boolean> { view.moved(moved) }.get())
        assertEquals(moved, view.file)
        assertEquals(FileSide.PRIVATE, view.side)
        assertEquals(moved.resolveSibling("http-client.env.json"), view.otherFile)
        assertFalse(view.otherFile.exists())
        assertFalse(WaitForAsyncUtils.asyncFx<Boolean> { view.moved(dir.resolve("other.json")) }.get())
    }
}
