package dev.sluide.editorarea

import dev.sluide.fileviewers.DefaultTextFileViewer
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** UI tests of [EditorArea.onFileClosed]. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EditorAreaFileClosedUiTest : EditorAreaUiFixture() {
    private val closed = mutableListOf<Path>()

    @Start
    fun start(stage: Stage) {
        startView(stage)
        editor.onFileClosed = { closed.add(it) }
    }

    @Test
    fun `closing a file tab reports its path once`(robot: FxRobot) {
        val a = textFile("a.txt")
        val b = textFile("b.txt")
        open(robot, a, b)
        val menu = editor.tabStrip.contextMenuOf(checkNotNull(editor.editorTabs.find(a)))

        robot.interact { menu.items.single { it.text == "Close" }.fire() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(a), closed)
    }

    @Test
    fun `Close Others reports each closed file once`(robot: FxRobot) {
        val files = listOf("a.txt", "b.txt", "c.txt").map { textFile(it) }
        open(robot, *files.toTypedArray())
        val menu = editor.tabStrip.contextMenuOf(checkNotNull(editor.editorTabs.find(files[1])))

        robot.interact { menu.items.single { it.text == "Close Others" }.fire() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(files[0], files[2]), closed)
    }

    @Test
    fun `closeAll reports every file but not the other tabs`(robot: FxRobot) {
        val a = textFile("a.txt")
        val b = textFile("b.txt")
        open(robot, a)
        robot.interact { editor.open(WarningTab("Terminal 1", null)) }
        open(robot, b)

        robot.interact { editor.closeAll() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(a, b), closed)
    }

    @Test
    fun `closing a tab that shows no file reports nothing`(robot: FxRobot) {
        val terminal = WarningTab("Terminal 1", null)
        robot.interact { editor.open(terminal) }

        robot.interact { editor.editorTabs.close(terminal) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(emptyList(), closed)
    }

    @Test
    fun `replacing a tab by another view of the same file reports nothing`(robot: FxRobot) {
        val a = textFile("a.txt")
        open(robot, a)

        robot.interact {
            val replacement = OpenFile(a, DefaultTextFileViewer().open(a))
            editor.editorTabs.replace(checkNotNull(editor.editorTabs.find(a)), replacement)
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(emptyList(), closed)
        assertEquals(listOf(a), editor.openPaths)
    }
}
