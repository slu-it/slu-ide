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

        robot.interact { container.children.setAll(DefaultTextFileViewer().open(file)) }
        WaitForAsyncUtils.waitForFxEvents()

        val text = robot.lookup(".text-area").queryAs(TextArea::class.java)
        assertEquals("fun main() {}\n", text.text)
        assertFalse(text.isEditable)
    }
}
