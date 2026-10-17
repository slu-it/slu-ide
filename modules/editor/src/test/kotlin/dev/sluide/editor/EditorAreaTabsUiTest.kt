package dev.sluide.editor

import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.input.MouseButton
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UI tests of the editor area: opening, closing, scrolling, dragging and the context menu of tabs. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EditorAreaTabsUiTest : EditorAreaUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

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
        assertEquals(
            "file-number-7.txt",
            strip
                .visibleTabs()
                .last()
                .title.value,
            "selected tab is scrolled into view",
        )
        assertTrue(strip.scrollRightButton.isDisable, "already at the end")
    }

    @Test
    fun `scroll buttons move the visible tabs without changing the selection`(robot: FxRobot) {
        robot.interact { stage.width = 300.0 }
        val files = Array(8) { textFile("file-number-$it.txt") }
        open(robot, *files)
        val strip = editor.tabStrip

        fun firstVisibleIndex() = files.indexOf((strip.visibleTabs().first() as OpenFile).path)
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
        assertEquals(strip.hiddenTabs().map { it.title.value }, items.map { it.text })
        assertEquals("file-number-0.txt", items.first().text)

        robot.interact {
            strip.overflowMenu.hide()
            items.first().fire()
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(files.first(), editor.selectedPath)
        assertEquals(
            "file-number-0.txt",
            strip
                .visibleTabs()
                .first()
                .title.value,
        )
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
    fun `dragging a tab moves it and keeps it selected`(robot: FxRobot) {
        val a = textFile("a.txt")
        val b = textFile("b.txt")
        val c = textFile("c.txt")
        open(robot, a, b, c)
        val width = tabLabel(robot, "a.txt").parent.layoutBounds.width

        dragBy(robot, tabLabel(robot, "a.txt"), width * 2)

        assertEquals(listOf("b.txt", "c.txt", "a.txt"), tabTitles(robot))
        assertEquals(listOf(b, c, a), editor.openPaths)
        assertEquals(a, editor.selectedPath)
        assertEquals("content of a.txt", shownText(robot))
        assertTrue(robot.lookup(".editor-tab.dragging").queryAll<Node>().isEmpty())

        dragBy(robot, tabLabel(robot, "c.txt"), -width * 5)

        assertEquals(listOf("c.txt", "b.txt", "a.txt"), tabTitles(robot))
        assertEquals(c, editor.selectedPath)
    }

    @Test
    fun `a tab moves only once the mouse moved a few pixels, and not from its close button`(robot: FxRobot) {
        val a = textFile("a.txt")
        open(robot, a, textFile("b.txt"))

        dragBy(robot, tabLabel(robot, "a.txt"), 4.0)
        assertEquals(listOf("a.txt", "b.txt"), tabTitles(robot))
        assertEquals(a, editor.selectedPath)

        val close = tabLabel(robot, "a.txt").parent.lookup(".close-button")
        dragBy(robot, close, 300.0, release = false)
        // Released on the title: a release on the button itself would close the tab, as no exit event disarms it.
        releaseOn(robot, tabLabel(robot, "a.txt"), 300.0)

        assertEquals(listOf("a.txt", "b.txt"), tabTitles(robot))
    }

    @Test
    fun `a dragged file tab keeps its watch and reloads`(robot: FxRobot) {
        val a = textFile("a.txt", "first")
        val b = textFile("b.txt")
        open(robot, a, b)

        dragBy(robot, tabLabel(robot, "a.txt"), 200.0)
        assertEquals(listOf(b, a), editor.openPaths)
        assertEquals(2, editor.watchCount)

        a.writeText("second")

        eventually(robot) { assertEquals("second", shownText(robot)) }
    }

    @Test
    fun `dragging a tab beyond the edge of an overflowing row scrolls it`(robot: FxRobot) {
        robot.interact { stage.width = 300.0 }
        val files = Array(8) { textFile("file-number-$it.txt") }
        open(robot, *files)
        open(robot, files.first())
        val strip = editor.tabStrip
        val label = tabLabel(robot, "file-number-0.txt")

        dragBy(robot, label, stage.width, release = false)
        eventually(robot) { assertEquals(files.first(), editor.openPaths.last()) }
        releaseOn(robot, label, stage.width)

        assertEquals(files.first(), editor.selectedPath)
        assertEquals(files.first(), (strip.visibleTabs().last() as OpenFile).path)
        assertTrue(strip.scrollRightButton.isDisable, "scrolled to the end")
    }

    @Test
    fun `middle click closes a tab`(robot: FxRobot) {
        open(robot, textFile("a.txt"), textFile("b.txt"))

        robot.interact { click(tabLabel(robot, "a.txt"), MouseButton.MIDDLE) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("b.txt"), tabTitles(robot))
    }

    @Test
    fun `closeAll removes all tabs and the shown content`(robot: FxRobot) {
        open(robot, textFile("a.txt"), textFile("b.txt"))

        robot.interact { editor.closeAll() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(emptyList(), tabTitles(robot))
        assertNull(shownText(robot))
        assertNull(editor.selectedPath)
    }

    @Test
    fun `right-click on a tab opens a menu with close actions for that tab`(robot: FxRobot) {
        val files = listOf("a.txt", "b.txt", "c.txt", "d.txt").map { textFile(it) }
        open(robot, *files.toTypedArray())
        val b = checkNotNull(editor.editorTabs.find(files[1]))
        val menu = editor.tabStrip.contextMenuOf(b)
        // Without a window manager the popup may hide again right away, so record that it was shown at all.
        var shown = false
        menu.showingProperty().subscribe { showing -> if (showing) shown = true }

        robot.interact { requestContextMenu(tabLabel(robot, "b.txt")) }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(shown)
        val items = menu.items.filterNot { it is SeparatorMenuItem }
        assertEquals(
            listOf("Close", "Close Others", "Close Tabs to the Left", "Close Tabs to the Right", "Close All"),
            items.map { it.text },
        )
        assertTrue(items.none { it.isDisable })
        assertEquals(files.last(), editor.selectedPath, "right-click does not select the tab")

        robot.interact {
            menu.hide()
            items.single { it.text == "Close Tabs to the Right" }.fire()
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("a.txt", "b.txt"), tabTitles(robot))
        assertEquals(files[1], editor.selectedPath)
    }

    @Test
    fun `context menu disables actions that would close nothing`(robot: FxRobot) {
        val a = textFile("a.txt")
        open(robot, a)

        robot.interact { requestContextMenu(tabLabel(robot, "a.txt")) }
        WaitForAsyncUtils.waitForFxEvents()

        val menu = editor.tabStrip.contextMenuOf(checkNotNull(editor.editorTabs.find(a)))
        val disabled = menu.items.filter { it !is SeparatorMenuItem && it.isDisable }.map { it.text }
        assertEquals(listOf("Close Others", "Close Tabs to the Left", "Close Tabs to the Right"), disabled)
        robot.interact { menu.hide() }
    }

    @Test
    fun `context menu Close Others and Close All`(robot: FxRobot) {
        val files = listOf("a.txt", "b.txt", "c.txt").map { textFile(it) }
        open(robot, *files.toTypedArray())
        val menu = editor.tabStrip.contextMenuOf(checkNotNull(editor.editorTabs.find(files[0])))

        robot.interact { menu.items.single { it.text == "Close Others" }.fire() }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("a.txt"), tabTitles(robot))
        assertEquals("content of a.txt", shownText(robot))

        robot.interact { menu.items.single { it.text == "Close All" }.fire() }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), tabTitles(robot))
    }
}
