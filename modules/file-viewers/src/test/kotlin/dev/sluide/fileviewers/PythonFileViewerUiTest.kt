package dev.sluide.fileviewers

import javafx.application.ColorScheme
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.paint.Paint
import javafx.scene.text.Text
import javafx.stage.Stage
import org.fxmisc.flowless.VirtualizedScrollPane
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
    fun `shows the file editable with highlighted tokens`(robot: FxRobot) {
        val source = "def main():  # entry\n    return 'done'\n"
        val file = dir.resolve("main.py").apply { writeText(source) }

        robot.interact { container.children.setAll(PythonFileViewer().open(file).node) }
        WaitForAsyncUtils.waitForFxEvents()

        val area = robot.lookup(".code-area").queryAs(CodeArea::class.java)
        assertEquals(source, area.text)
        assertTrue(area.isEditable)
        assertTrue("keyword" in area.getStyleOfChar(source.indexOf("def") + 1))
        assertTrue("comment" in area.getStyleOfChar(source.indexOf("# entry") + 1))
        assertTrue("string" in area.getStyleOfChar(source.indexOf("'done'") + 1))
        assertTrue(area.getStyleOfChar(source.indexOf("main") + 1).isEmpty())
    }

    @Test
    fun `token colours follow the colour scheme of the window`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("def main():\n    pass\n") }
        robot.interact { container.children.setAll(PythonFileViewer().open(file).node) }

        fun keywordFill(scheme: ColorScheme): Paint {
            robot.interact { container.scene.preferences.colorScheme = scheme }
            WaitForAsyncUtils.waitForFxEvents()
            return robot
                .lookup(".keyword")
                .queryAllAs(Text::class.java)
                .first { it.text == "def" }
                .fill
        }

        assertEquals(Color.web("#0033b3"), keywordFill(ColorScheme.LIGHT))
        assertEquals(Color.web("#cc7832"), keywordFill(ColorScheme.DARK))
        assertEquals(Color.web("#0033b3"), keywordFill(ColorScheme.LIGHT))
    }

    @Test
    fun `a long file opens at its top`(robot: FxRobot) {
        val file = dir.resolve("long.py").apply { writeText((1..300).joinToString("\n") { "x$it = $it" }) }

        robot.interact { container.children.setAll(PythonFileViewer().open(file).node) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(0, robot.lookup(".code-area").queryAs(CodeArea::class.java).firstVisibleParToAllParIndex())
    }

    @Test
    fun `shows an empty file`(robot: FxRobot) {
        val file = dir.resolve("empty.py").apply { writeText("") }

        robot.interact { container.children.setAll(PythonFileViewer().open(file).node) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals("", robot.lookup(".code-area").queryAs(CodeArea::class.java).text)
    }

    @Test
    fun `reload shows and highlights the new content and keeps the scroll position`(robot: FxRobot) {
        val file = dir.resolve("long.py").apply { writeText((1..300).joinToString("\n") { "x$it = $it" }) }
        val view = WaitForAsyncUtils.asyncFx<FileView> { PythonFileViewer().open(file) }.get()
        val pane = view.node as VirtualizedScrollPane<*>
        robot.interact { container.children.setAll(view.node) }
        robot.interact { pane.scrollYToPixel(1000.0) }
        WaitForAsyncUtils.waitForFxEvents()
        val before = pane.estimatedScrollY

        file.writeText((1..300).joinToString("\n") { "x$it = $it" } + "\ndef added(): pass")
        robot.interact { view.reload() }
        WaitForAsyncUtils.waitForFxEvents()

        val area = robot.lookup(".code-area").queryAs(CodeArea::class.java)
        assertTrue(area.text.endsWith("def added(): pass"))
        assertTrue("keyword" in area.getStyleOfChar(area.text.lastIndexOf("def") + 1))
        assertTrue(before > 500.0, "scrolled down before the reload")
        assertEquals(before, pane.estimatedScrollY, 20.0)
    }

    @Test
    fun `reload after the file got shorter clamps the caret`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("print('a fairly long first version')\n") }
        val view = WaitForAsyncUtils.asyncFx<FileView> { PythonFileViewer().open(file) }.get()
        robot.interact { container.children.setAll(view.node) }
        val area = robot.lookup(".code-area").queryAs(CodeArea::class.java)
        robot.interact { area.moveTo(area.length) }

        file.writeText("x = 1")
        robot.interact { view.reload() }

        assertEquals("x = 1", area.text)
        assertEquals(5, area.caretPosition)
    }
}
