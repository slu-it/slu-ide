package dev.sluide.fileviewers

import javafx.scene.Scene
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
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Edits files in a real [CodeView]. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class CodeViewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 500.0, 300.0)
        stage.show()
    }

    private fun open(
        file: Path,
        robot: FxRobot,
    ): CodeView {
        val view = WaitForAsyncUtils.asyncFx<CodeView> { CodeView(file, PythonFileViewer().highlighter) }.get()
        robot.interact { container.children.setAll(view.node) }
        return view
    }

    @Test
    fun `an edit makes the view dirty and undoing it clean again`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("x = 1\n") }
        val view = open(file, robot)
        assertTrue(view.area.isEditable)
        assertFalse(view.dirty.get(), "clean after opening")

        robot.interact { view.area.insertText(0, "y = 2\n") }
        assertTrue(view.dirty.get())

        robot.interact { view.area.undo() }
        assertEquals("x = 1\n", view.area.text)
        assertFalse(view.dirty.get())
    }

    @Test
    fun `save writes the text in place and makes the view clean`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("x = 1\n") }
        val view = open(file, robot)

        robot.interact {
            view.area.insertText(0, "y = 2\n")
            view.save()
        }

        assertEquals("y = 2\nx = 1\n", file.readText())
        assertFalse(view.dirty.get())
    }

    @Test
    fun `typing after a save is a change of its own`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("") }
        val view = open(file, robot)

        robot.interact {
            view.area.insertText(0, "a")
            view.save()
            // Right after the first edit, which the undo manager would otherwise merge with this one.
            view.area.insertText(1, "b")
        }
        assertTrue(view.dirty.get())

        robot.interact { view.area.undo() }
        assertEquals("a", view.area.text)
        assertFalse(view.dirty.get(), "back at the saved text")
    }

    @Test
    fun `saving keeps the line endings of the file`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("a = 1\r\nb = 2\r\n") }
        val view = open(file, robot)
        assertEquals("a = 1\nb = 2\n", view.area.text)

        robot.interact {
            view.area.insertText(view.area.length, "c = 3\n")
            view.save()
        }

        assertContentEquals("a = 1\r\nb = 2\r\nc = 3\r\n".toByteArray(), file.readBytes())
    }

    @Test
    fun `reloading an unchanged file with CRLF line endings keeps the caret`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("a = 1\r\nb = 2\r\n") }
        val view = open(file, robot)
        robot.interact { view.area.moveTo(3) }

        robot.interact { view.reload() }

        assertEquals(3, view.area.caretPosition)
        assertFalse(view.dirty.get())
    }
}
