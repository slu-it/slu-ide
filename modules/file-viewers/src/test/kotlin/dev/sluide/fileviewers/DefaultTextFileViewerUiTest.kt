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
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun open(
        file: Path,
        robot: FxRobot,
    ): Pair<FileView, CodeArea> {
        val view = WaitForAsyncUtils.asyncFx<FileView> { DefaultTextFileViewer().open(file) }.get()
        robot.interact { container.children.setAll(view.node) }
        return view to robot.lookup(".code-area").queryAs(CodeArea::class.java)
    }

    @Test
    fun `shows the file content editable and unhighlighted`(robot: FxRobot) {
        val file = dir.resolve("notes.txt").apply { writeText("fun main() {}\n") }

        val (view, area) = open(file, robot)

        assertEquals("fun main() {}\n", area.text)
        assertTrue(area.isEditable)
        assertTrue(area.getStyleSpans(0, area.length).all { it.style.isEmpty() })
        assertTrue(view is EditableFileView)
    }

    @Test
    fun `edits are saved to the file`(robot: FxRobot) {
        val file = dir.resolve("notes.txt").apply { writeText("first\n") }
        val (view, area) = open(file, robot)

        robot.interact {
            area.appendText("second\n")
            (view as EditableFileView).save()
        }

        assertEquals("first\nsecond\n", file.readText())
    }

    @Test
    fun `reload shows the new content`(robot: FxRobot) {
        val file = dir.resolve("long.txt").apply { writeText((1..300).joinToString("\n") { "line $it" }) }
        val (view, area) = open(file, robot)

        file.writeText((1..301).joinToString("\n") { "line $it" })
        robot.interact { view.reload() }

        assertTrue(area.text.endsWith("line 301"))
    }
}
