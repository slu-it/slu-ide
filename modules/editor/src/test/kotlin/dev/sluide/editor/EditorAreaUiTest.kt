package dev.sluide.editor

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Renders the real editor area in a stage. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EditorAreaUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var stage: Stage
    private lateinit var editor: EditorArea

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        val fallback = DefaultTextFileViewer()
        editor = EditorArea(FileViewers(listOf(fallback), fallback))
        stage.scene = Scene(editor, 800.0, 400.0)
        stage.show()
    }

    private fun textFile(
        name: String,
        content: String = "content of $name",
    ) = dir.resolve(name).apply { writeText(content) }

    private fun open(
        robot: FxRobot,
        vararg files: Path,
    ): List<Boolean> {
        val results = mutableListOf<Boolean>()
        robot.interact { files.forEach { results += editor.open(it) } }
        WaitForAsyncUtils.waitForFxEvents()
        return results
    }

    private fun tabTitles(robot: FxRobot): List<String> =
        robot
            .lookup(".editor-tab .label")
            .queryAllAs(Label::class.java)
            .sortedBy { it.localToScene(0.0, 0.0).x }
            .map { it.text }

    private fun shownText(robot: FxRobot): String? =
        robot
            .lookup(".editor-content .text-area")
            .queryAllAs(TextArea::class.java)
            .singleOrNull()
            ?.text

    @Test
    fun `opening files adds selected tabs showing their content`(robot: FxRobot) {
        val a = textFile("a.txt")
        val b = textFile("b.kt")

        assertEquals(listOf(true, true), open(robot, a, b))

        assertEquals(listOf("a.txt", "b.kt"), tabTitles(robot))
        assertEquals(b, editor.selectedPath)
        assertEquals("content of b.kt", shownText(robot))
    }

    @Test
    fun `opening an open file again selects its tab`(robot: FxRobot) {
        val a = textFile("a.txt")
        val b = textFile("b.txt")
        open(robot, a, b)

        assertEquals(listOf(true), open(robot, a))

        assertEquals(listOf("a.txt", "b.txt"), tabTitles(robot))
        assertEquals("content of a.txt", shownText(robot))
    }

    @Test
    fun `binary files are not opened`(robot: FxRobot) {
        val binary = dir.resolve("image.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x00, 0x47)) }

        assertEquals(listOf(false), open(robot, binary))

        assertEquals(emptyList(), tabTitles(robot))
        assertNull(shownText(robot))
    }

    @Test
    fun `close button closes the tab and selects its neighbour`(robot: FxRobot) {
        val a = textFile("a.txt")
        val b = textFile("b.txt")
        open(robot, a, b)

        val closeB = tabLabel(robot, "b.txt").parent.lookup(".close-button") as Button
        robot.interact { closeB.fire() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("a.txt"), tabTitles(robot))
        assertEquals("content of a.txt", shownText(robot))
    }

    @Test
    fun `scroll buttons and overflow menu appear only when tabs do not fit`(robot: FxRobot) {
        open(robot, textFile("a.txt"))
        val strip = editor.tabStrip
        assertFalse(strip.scrollLeftButton.isVisible)
        assertFalse(strip.overflowMenu.isVisible)

        robot.interact { stage.width = 300.0 }
        open(robot, *Array(8) { textFile("file-number-$it.txt") })

        assertTrue(strip.scrollLeftButton.isVisible)
        assertTrue(strip.scrollRightButton.isVisible)
        assertTrue(strip.overflowMenu.isVisible)
        assertEquals("file-number-7.txt", strip.visibleFiles().last().name, "selected tab is scrolled into view")
        assertTrue(strip.scrollRightButton.isDisable, "already at the end")
    }

    @Test
    fun `scroll buttons move the visible tabs without changing the selection`(robot: FxRobot) {
        robot.interact { stage.width = 300.0 }
        val files = Array(8) { textFile("file-number-$it.txt") }
        open(robot, *files)
        val strip = editor.tabStrip

        fun firstVisibleIndex() = files.indexOf(strip.visibleFiles().first().path)
        val before = firstVisibleIndex()

        robot.interact { strip.scrollLeftButton.fire() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(before - 1, firstVisibleIndex())
        assertEquals(files.last(), editor.selectedPath)
        assertFalse(strip.scrollRightButton.isDisable)
    }

    @Test
    fun `overflow menu lists hidden tabs and selecting one reveals it`(robot: FxRobot) {
        robot.interact { stage.width = 300.0 }
        val files = Array(8) { textFile("file-number-$it.txt") }
        open(robot, *files)
        val strip = editor.tabStrip

        robot.interact { strip.overflowMenu.show() }
        WaitForAsyncUtils.waitForFxEvents()
        val items = strip.overflowMenu.items
        assertEquals(strip.hiddenFiles().map { it.name }, items.map { it.text })
        assertEquals("file-number-0.txt", items.first().text)

        robot.interact {
            strip.overflowMenu.hide()
            items.first().fire()
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(files.first(), editor.selectedPath)
        assertEquals("file-number-0.txt", strip.visibleFiles().first().name)
        assertEquals("content of file-number-0.txt", shownText(robot))
    }

    @Test
    fun `clicking a tab selects it, clicking another tab's close button keeps the selection`(robot: FxRobot) {
        val a = textFile("a.txt")
        val b = textFile("b.txt")
        val c = textFile("c.txt")
        open(robot, a, b, c)

        robot.interact { click(tabLabel(robot, "a.txt")) }
        assertEquals(a, editor.selectedPath)

        robot.interact { click(tabLabel(robot, "b.txt").parent.lookup(".close-button")) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("a.txt", "c.txt"), tabTitles(robot))
        assertEquals(a, editor.selectedPath)
        assertEquals("content of a.txt", shownText(robot))
    }

    @Test
    fun `middle click closes a tab`(robot: FxRobot) {
        open(robot, textFile("a.txt"), textFile("b.txt"))

        robot.interact { click(tabLabel(robot, "a.txt"), MouseButton.MIDDLE) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("b.txt"), tabTitles(robot))
    }

    private fun tabLabel(
        robot: FxRobot,
        name: String,
    ): Label = robot.lookup(".editor-tab .label").queryAllAs(Label::class.java).single { it.text == name }

    /** Sends a press/release/click through the scene graph without OS input, see docs/testing.md. */
    private fun click(
        node: Node,
        button: MouseButton = MouseButton.PRIMARY,
    ) {
        for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            val down = type == MouseEvent.MOUSE_PRESSED
            Event.fireEvent(
                node,
                MouseEvent(
                    type,
                    1.0,
                    1.0,
                    1.0,
                    1.0,
                    button,
                    1,
                    false,
                    false,
                    false,
                    false,
                    down && button == MouseButton.PRIMARY,
                    down && button == MouseButton.MIDDLE,
                    false,
                    false,
                    false,
                    true,
                    null,
                ),
            )
        }
    }
}
