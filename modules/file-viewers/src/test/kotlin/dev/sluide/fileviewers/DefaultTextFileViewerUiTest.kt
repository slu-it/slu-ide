package dev.sluide.fileviewers

import javafx.scene.Scene
import javafx.scene.control.TextArea
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Renders the real viewer in a stage. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class DefaultTextFileViewerUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 400.0, 300.0)
        stage.show()
    }

    @Test
    fun `shows the file content read-only`(robot: FxRobot) {
        val file = dir.resolve("Main.kt").apply { writeText("fun main() {}\n") }

        robot.interact { container.children.setAll(DefaultTextFileViewer().open(file).node) }
        WaitForAsyncUtils.waitForFxEvents()

        val text = robot.lookup(".text-area").queryAs(TextArea::class.java)
        assertEquals("fun main() {}\n", text.text)
        assertFalse(text.isEditable)
    }

    @Test
    fun `reload shows the new content and keeps the scroll position`(robot: FxRobot) {
        val file = dir.resolve("long.txt").apply { writeText((1..300).joinToString("\n") { "line $it" }) }
        val view = WaitForAsyncUtils.asyncFx<FileView> { DefaultTextFileViewer().open(file) }.get()
        val area = view.node as TextArea
        robot.interact { container.children.setAll(view.node) }
        robot.interact { area.scrollTop = 1000.0 }
        WaitForAsyncUtils.waitForFxEvents()

        file.writeText((1..301).joinToString("\n") { "line $it" })
        robot.interact { view.reload() }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(area.text.endsWith("line 301"))
        assertEquals(1000.0, area.scrollTop, 1.0)
    }

    @Test
    fun `reload after the file got shorter clamps the caret`(robot: FxRobot) {
        val file = dir.resolve("short.txt").apply { writeText("a fairly long first version") }
        val view = WaitForAsyncUtils.asyncFx<FileView> { DefaultTextFileViewer().open(file) }.get()
        val area = view.node as TextArea
        robot.interact {
            container.children.setAll(view.node)
            area.positionCaret(area.length)
        }

        file.writeText("short")
        robot.interact { view.reload() }

        assertEquals("short", area.text)
        assertEquals(5, area.caretPosition)
    }
}
