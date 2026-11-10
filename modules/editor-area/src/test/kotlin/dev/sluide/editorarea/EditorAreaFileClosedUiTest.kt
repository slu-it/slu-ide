package dev.sluide.editorarea

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileView
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

        assertEquals(listOf(a), closed)
    }

    @Test
    fun `Close Others reports each closed file once`(robot: FxRobot) {
        val files = listOf("a.txt", "b.txt", "c.txt").map { textFile(it) }
        open(robot, *files.toTypedArray())
        val menu = editor.tabStrip.contextMenuOf(checkNotNull(editor.editorTabs.find(files[1])))

        robot.interact { menu.items.single { it.text == "Close Others" }.fire() }

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

        assertEquals(listOf(a, b), closed)
    }

    @Test
    fun `moving a file tab to the other side and back keeps its watch and reports nothing`(robot: FxRobot) {
        val a = textFile("a.txt")
        val b = textFile("b.txt")
        open(robot, a, b)
        val tab = checkNotNull(editor.editorTabs.find(a))

        for (side in listOf(Side.RIGHT, Side.LEFT)) {
            robot.interact { editor.editorTabs.moveTo(tab, side, 0) }

            assertEquals(side, editor.editorTabs.sideOf(tab))
            assertEquals(2, editor.watchCount, "after the move to the $side side")
        }
        assertEquals(emptyList(), closed)
    }

    @Test
    fun `closing a tab that shows no file reports nothing`(robot: FxRobot) {
        val terminal = WarningTab("Terminal 1", null)
        robot.interact { editor.open(terminal) }

        robot.interact { editor.editorTabs.close(terminal) }

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

    @Test
    fun `the report of a closed file comes after its view is disposed, for every way of closing`(robot: FxRobot) {
        val events = mutableListOf<String>()
        editor.onFileClosed = { events += "closed ${it.fileName}" }
        val files = listOf("a.txt", "b.txt", "c.txt").map { textFile(it) }
        robot.interact {
            for (file in files) {
                val inner = DefaultTextFileViewer().open(file)
                val view =
                    object : FileView by inner {
                        override fun dispose() {
                            events += "disposed ${file.fileName}"
                            inner.dispose()
                        }
                    }
                editor.editorTabs.open(OpenFile(file, view), select = true)
            }
        }

        robot.interact { editor.editorTabs.close(checkNotNull(editor.editorTabs.find(files[0]))) }
        robot.interact { editor.closeAll() }

        assertEquals(listOf("disposed a.txt", "closed a.txt"), events.take(2))
        // Several tabs closed by one operation are all disposed before the first report.
        assertEquals(setOf("disposed b.txt", "disposed c.txt"), events.subList(2, 4).toSet())
        assertEquals(setOf("closed b.txt", "closed c.txt"), events.subList(4, 6).toSet())
    }
}
