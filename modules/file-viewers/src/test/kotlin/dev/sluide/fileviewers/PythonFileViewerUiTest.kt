package dev.sluide.fileviewers

import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
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
class PythonFileViewerUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 500.0, 300.0)
        stage.show()
    }

    @Test
    fun `shows the file read-only with highlighted tokens`(robot: FxRobot) {
        val source = "def main():  # entry\n    return 'done'\n"
        val file = dir.resolve("main.py").apply { writeText(source) }

        robot.interact { container.children.setAll(PythonFileViewer().open(file)) }
        WaitForAsyncUtils.waitForFxEvents()

        val area = robot.lookup(".code-area").queryAs(CodeArea::class.java)
        assertEquals(source, area.text)
        assertFalse(area.isEditable)
        assertTrue("keyword" in area.getStyleOfChar(source.indexOf("def") + 1))
        assertTrue("comment" in area.getStyleOfChar(source.indexOf("# entry") + 1))
        assertTrue("string" in area.getStyleOfChar(source.indexOf("'done'") + 1))
        assertTrue(area.getStyleOfChar(source.indexOf("main") + 1).isEmpty())
    }

    @Test
    fun `shows an empty file`(robot: FxRobot) {
        val file = dir.resolve("empty.py").apply { writeText("") }

        robot.interact { container.children.setAll(PythonFileViewer().open(file)) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals("", robot.lookup(".code-area").queryAs(CodeArea::class.java).text)
    }
}
