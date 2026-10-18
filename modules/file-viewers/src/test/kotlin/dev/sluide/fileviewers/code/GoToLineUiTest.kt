package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.press
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shortcut+G in a real [CodeView], with the dialog replaced. Needs a display (`uiTest`). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GoToLineUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 400.0, 200.0)
        stage.show()
    }

    private fun open(robot: FxRobot): CodeView {
        val file = dir.resolve("lines.txt").apply { writeText((1..100).joinToString("\n") { "line $it" }) }
        val view = WaitForAsyncUtils.asyncFx<CodeView> { CodeView(file, null) }.get()
        robot.interact { container.children.setAll(view.node) }
        return view
    }

    @Test
    fun `shortcut+g moves the caret to the line and column entered and scrolls there`(robot: FxRobot) {
        val view = open(robot)
        robot.interact { view.area.moveTo(1, 2) }
        val asked = mutableListOf<String>()
        view.goToLine.ask = {
            asked += it
            "80:3"
        }

        robot.press(view.area, KeyCode.G, shortcut = true)
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("2:3"), asked, "offers the caret's position")
        assertEquals(79, view.area.currentParagraph)
        assertEquals(2, view.area.caretColumn)
        assertTrue(79 in view.area.firstVisibleParToAllParIndex()..view.area.lastVisibleParToAllParIndex())
    }

    @Test
    fun `positions beyond the text are clamped and other input is ignored`(robot: FxRobot) {
        val view = open(robot)

        view.goToLine.ask = { "500:99" }
        robot.press(view.area, KeyCode.G, shortcut = true)
        assertEquals(view.area.length, view.area.caretPosition)

        view.goToLine.ask = { "abc" }
        robot.press(view.area, KeyCode.G, shortcut = true)
        view.goToLine.ask = { null }
        robot.press(view.area, KeyCode.G, shortcut = true)
        assertEquals(view.area.length, view.area.caretPosition)
    }
}
