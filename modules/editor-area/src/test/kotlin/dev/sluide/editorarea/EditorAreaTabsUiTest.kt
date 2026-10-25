package dev.sluide.editorarea

import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.TextDiff
import dev.sluide.fileviewers.ViewActivity
import javafx.beans.property.SimpleStringProperty
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.TextField
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * UI tests of the editor area: opening, closing, scrolling, dragging and the context menu of tabs. Needs a display, see
 * the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EditorAreaTabsUiTest : EditorAreaUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    /** Opens [count] tabs that are no files, as light as tabs get, to fill the tab row. */
    private fun openTabs(
        robot: FxRobot,
        count: Int = 8,
    ): List<WarningTab> {
        val tabs = List(count) { WarningTab("file-number-$it.txt", null) }
        robot.interact { tabs.forEach(editor::open) }
        return tabs
    }

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
    fun `opening a text file focuses its text at the start`(robot: FxRobot) {
        val field = fieldAbove(robot)
        robot.interact { field.requestFocus() }

        open(robot, textFile("a.txt", (1..200).joinToString("\n", postfix = "\n") { "line $it" }))

        eventually(robot) { assertSame(shownArea(robot), stage.scene.focusOwner) }
        robot.interact {
            assertEquals(0, shownArea(robot).caretPosition)
            assertEquals(0, shownArea(robot).firstVisibleParToAllParIndex())
        }
    }

    @Test
    fun `opening an open file again focuses it, keeping its caret, whether its tab was selected or not`(
        robot: FxRobot,
    ) {
        val field = fieldAbove(robot)
        val a = textFile("a.txt", (1..200).joinToString("\n", postfix = "\n") { "line $it" })
        val b = textFile("b.txt")
        open(robot, a)
        robot.interact {
            shownArea(robot).moveTo(150, 0)
            shownArea(robot).requestFollowCaret()
        }
        WaitForAsyncUtils.waitForFxEvents()
        val caret = WaitForAsyncUtils.asyncFx<Int> { shownArea(robot).caretPosition }.get()
        val top = WaitForAsyncUtils.asyncFx<Int> { shownArea(robot).firstVisibleParToAllParIndex() }.get()
        assertTrue(top > 0, "scrolled down")

        robot.interact { field.requestFocus() }
        open(robot, a)
        eventually(robot) { assertSame(shownArea(robot), stage.scene.focusOwner, "selected") }
        robot.interact {
            assertEquals(caret, shownArea(robot).caretPosition)
            assertEquals(top, shownArea(robot).firstVisibleParToAllParIndex())
        }

        open(robot, b)
        robot.interact { field.requestFocus() }
        open(robot, a)
        eventually(robot) { assertSame(shownArea(robot), stage.scene.focusOwner, "not selected") }
        robot.interact { assertEquals(caret, shownArea(robot).caretPosition) }
    }

    @Test
    fun `clicking a text tab focuses its text, also the selected one`(robot: FxRobot) {
        val field = fieldAbove(robot)
        open(robot, textFile("a.txt", "first\nsecond\n"), textFile("b.txt"))
        robot.interact { field.requestFocus() }

        robot.interact { click(tabLabel(robot, "a.txt")) }

        eventually(robot) { assertSame(shownArea(robot), stage.scene.focusOwner) }
        assertEquals("first\nsecond\n", shownText(robot))

        robot.interact { field.requestFocus() }
        robot.interact { click(tabLabel(robot, "a.txt")) }
        eventually(robot) { assertSame(shownArea(robot), stage.scene.focusOwner, "the selected tab") }
    }

    @Test
    fun `closing the selected tab with the focus in it focuses the tab selected next`(robot: FxRobot) {
        fieldAbove(robot)
        val a = textFile("a.txt", "first\n")
        val b = textFile("b.txt")
        open(robot, a, b)
        eventually(robot) { assertSame(shownArea(robot), stage.scene.focusOwner) }

        robot.interact { editor.closeUnder(b, emptyList()) }

        eventually(robot) { assertSame(shownArea(robot), stage.scene.focusOwner) }
        assertEquals("first\n", shownText(robot))
    }

    @Test
    fun `a tab closed with the focus outside the area leaves it there, as when its file is deleted in the tree`(
        robot: FxRobot,
    ) {
        val field = fieldAbove(robot)
        val b = textFile("b.txt")
        open(robot, textFile("a.txt"), b)
        robot.interact { field.requestFocus() }

        robot.interact { editor.closeUnder(b, emptyList()) }

        assertEquals("content of a.txt", shownText(robot))
        assertSame(field, focusOwner())
    }

    @Test
    fun `binary files are not opened`(robot: FxRobot) {
        val binary = dir.resolve("image.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x00, 0x47)) }

        assertEquals(listOf(false), open(robot, binary))

        assertEquals(emptyList(), tabTitles(robot))
        assertNull(shownText(robot))
    }

    @Test
    fun `closeSelected closes the selected file tab and selects its neighbour`(robot: FxRobot) {
        open(robot, textFile("a.txt"), textFile("b.txt"))

        robot.interact { editor.closeSelected() }

        assertEquals(listOf("a.txt"), tabTitles(robot))
        assertEquals("content of a.txt", shownText(robot))
    }

    @Test
    fun `canCloseSelected follows the selection, and a tab not closable by shortcut stays`(robot: FxRobot) {
        val a = textFile("a.txt")
        val terminal = WarningTab("Terminal 1", null)
        assertFalse(editor.canCloseSelected.value)
        open(robot, a)
        assertTrue(editor.canCloseSelected.value)

        robot.interact { editor.open(terminal) }
        assertFalse(editor.canCloseSelected.value)
        robot.interact { editor.closeSelected() }
        assertEquals(listOf("a.txt", "Terminal 1"), tabTitles(robot))

        robot.interact { click(tabLabel(robot, "a.txt")) }
        assertTrue(editor.canCloseSelected.value)
    }

    @Test
    fun `scroll buttons and overflow menu appear only when tabs do not fit`(robot: FxRobot) {
        robot.interact { editor.open(WarningTab("a.txt", null)) }
        val strip = editor.tabStrip
        assertFalse(strip.scrollLeftButton.isVisible)
        assertFalse(strip.overflowMenu.isVisible)

        robot.interact { stage.width = 300.0 }
        openTabs(robot)

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
        val tabs = openTabs(robot)
        val strip = editor.tabStrip

        fun firstVisibleIndex() = tabs.indexOf(strip.visibleTabs().first())
        val before = firstVisibleIndex()

        robot.interact { strip.scrollLeftButton.fire() }

        assertEquals(before - 1, firstVisibleIndex())
        assertSame(tabs.last(), editor.selectedTab)
        assertFalse(strip.scrollRightButton.isDisable)
    }

    @Test
    fun `overflow menu lists hidden tabs and selecting one reveals it`(robot: FxRobot) {
        robot.interact { stage.width = 300.0 }
        val tabs = openTabs(robot)
        val strip = editor.tabStrip

        robot.interact { strip.overflowMenu.show() }
        val items = strip.overflowMenu.items
        assertEquals(strip.hiddenTabs().map { it.title.value }, items.map { it.text })
        assertEquals("file-number-0.txt", items.first().text)

        robot.interact {
            strip.overflowMenu.hide()
            items.first().fire()
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertSame(tabs.first(), editor.selectedTab)
        assertEquals(
            "file-number-0.txt",
            strip
                .visibleTabs()
                .first()
                .title.value,
        )
        assertEquals("file-number-0.txt", (editor.selectedTab?.content as Label).text)
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

        assertEquals(listOf("a.txt", "c.txt"), tabTitles(robot))
        assertEquals(a, editor.selectedPath)
        assertEquals("content of a.txt", shownText(robot))

        robot.interact { (tabLabel(robot, "a.txt").parent.lookup(".close-button") as Button).fire() }

        assertEquals(listOf("c.txt"), tabTitles(robot))
        assertEquals(c, editor.selectedPath, "closing the selected tab selects its neighbour")
        assertEquals("content of c.txt", shownText(robot))
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
        val tabs = openTabs(robot)
        robot.interact { editor.editorTabs.select(tabs.first()) }
        val strip = editor.tabStrip
        val label = tabLabel(robot, "file-number-0.txt")

        dragBy(robot, label, stage.width, release = false)
        eventually(robot) { assertSame(tabs.first(), editor.tabs.last()) }
        releaseOn(robot, label, stage.width)

        assertSame(tabs.first(), editor.selectedTab)
        assertSame(tabs.first(), strip.visibleTabs().last())
        assertTrue(strip.scrollRightButton.isDisable, "scrolled to the end")
    }

    @Test
    fun `dragging the last tab beyond the left edge of an overflowing row scrolls it back`(robot: FxRobot) {
        robot.interact { stage.width = 300.0 }
        val tabs = openTabs(robot)
        val strip = editor.tabStrip
        val label = tabLabel(robot, "file-number-7.txt")

        dragBy(robot, label, -stage.width, release = false)
        eventually(robot) { assertSame(tabs.last(), editor.tabs.first()) }
        releaseOn(robot, label, -stage.width)

        assertSame(tabs.last(), editor.selectedTab)
        assertSame(tabs.last(), strip.visibleTabs().first())
        assertTrue(strip.scrollLeftButton.isDisable, "scrolled to the start")
    }

    @Test
    fun `a tab closed while it is dragged leaves no dragging tab and its release does nothing`(robot: FxRobot) {
        val tabs = openTabs(robot, 3)
        val label = tabLabel(robot, "file-number-0.txt")

        dragBy(robot, label, 80.0, release = false)
        assertEquals(1, robot.lookup(".editor-tab.dragging").queryAll<Node>().size)
        robot.interact { editor.editorTabs.close(tabs.first()) }

        assertTrue(robot.lookup(".editor-tab.dragging").queryAll<Node>().isEmpty())
        releaseOn(robot, label, 80.0)
        assertEquals(tabs.drop(1), editor.tabs)
        assertTrue(robot.lookup(".editor-tab.dragging").queryAll<Node>().isEmpty())
    }

    /** A tab whose content is a text field, to see where the focus goes. */
    private class FieldTab : EditorTab {
        override val title = SimpleStringProperty("Fields")
        override val tooltip = "Fields"
        override val content = TextField()
    }

    @Test
    fun `focusSelected focuses the area without tabs, a file's view, its diff while shown, or another tab's content`(
        robot: FxRobot,
    ) {
        val field = fieldAbove(robot)
        robot.interact { field.requestFocus() }
        robot.interact { editor.focusSelected() }
        assertSame(editor, focusOwner(), "no tab: the area")

        val a = textFile("a.txt", "new\n")
        editor.diffOf = immediately { TextDiff("old\n", listOf(LineChange(0..0, 0..0))) }
        open(robot, a)
        robot.interact { field.requestFocus() }
        robot.interact { editor.focusSelected() }
        assertSame(shownArea(robot), focusOwner(), "a file: its view")

        robot.interact { editor.toggleDiff() }
        robot.interact { field.requestFocus() }
        robot.interact { editor.focusSelected() }
        val diff = robot.lookup(".editor-content .diff-view").queryAs(Node::class.java)
        assertTrue(focusOwner()?.isInside(diff) == true, "a diff shown: the diff")

        val fields = FieldTab()
        robot.interact { editor.open(fields) }
        robot.interact { field.requestFocus() }
        robot.interact { editor.focusSelected() }
        assertSame(fields.content, focusOwner(), "another tab: its content")
    }

    @Test
    fun `a file tab shows its view's activity as a marker before its title until it closes`(robot: FxRobot) {
        val viewer = BusyViewer()
        val area = areaWith(robot, viewer)
        robot.interact { area.open(textFile("build.busy")) }
        val view = viewer.views.single()
        val header = tabLabel(robot, "build.busy").parent

        // The style classes of the nodes before the title.
        fun markers() =
            header.childrenUnmodifiable
                .takeWhile { it !is Label }
                .flatMap { it.styleClass }
                .filter { it.startsWith("tab-activity-") }
        assertEquals(emptyList(), markers())

        robot.interact { view.activity.set(ViewActivity.RUNNING) }
        assertEquals(listOf("tab-activity-running"), markers())

        robot.interact { view.activity.set(ViewActivity.FAILED) }
        assertEquals(listOf("tab-activity-failed"), markers())
        assertEquals(listOf("build.busy"), tabTitles(robot))

        robot.interact { view.activity.set(ViewActivity.NONE) }
        assertEquals(emptyList(), markers())

        robot.interact { area.closeAll() }
        robot.interact { view.activity.set(ViewActivity.RUNNING) }
        assertEquals(emptyList(), markers(), "a closed tab's header no longer follows its view")
    }

    @Test
    fun `middle click closes a tab`(robot: FxRobot) {
        open(robot, textFile("a.txt"), textFile("b.txt"))

        robot.interact { click(tabLabel(robot, "a.txt"), MouseButton.MIDDLE) }

        assertEquals(listOf("b.txt"), tabTitles(robot))
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
        assertEquals(listOf("a.txt"), tabTitles(robot))
        assertEquals("content of a.txt", shownText(robot))

        robot.interact { menu.items.single { it.text == "Close All" }.fire() }
        assertEquals(emptyList(), tabTitles(robot))
    }

    @Test
    fun `file tabs have Show History and Select in Explorer in their menu while offered, each enabled per file`(
        robot: FxRobot,
    ) {
        val a = textFile("a.txt")
        val b = textFile("b.txt")
        open(robot, a, b)
        val other = WarningTab("Terminal 1", null)
        robot.interact { editor.open(other) }
        val shown = mutableListOf<java.nio.file.Path>()
        val selected = mutableListOf<java.nio.file.Path>()
        editor.tabMenu.showHistory = { shown.add(it) }
        editor.tabMenu.canShowHistory = { it == a }
        editor.tabMenu.selectInExplorer = { selected.add(it) }
        editor.tabMenu.canSelectInExplorer = { it == b }

        fun items(title: String): List<MenuItem> {
            robot.interact { requestContextMenu(tabLabel(robot, title)) }
            return WaitForAsyncUtils
                .asyncFx<List<MenuItem>> {
                    val menu = editor.tabStrip.contextMenuOf(editor.tabs.single { it.title.value == title })
                    menu.hide()
                    menu.items.toList()
                }.get()
        }

        fun List<MenuItem>.named(text: String) = singleOrNull { it.text == text }

        fun disabled(item: MenuItem?) = WaitForAsyncUtils.asyncFx<Boolean> { checkNotNull(item).isDisable }.get()

        val forA = items("a.txt")
        assertFalse(disabled(forA.named("Show History")))
        assertTrue(disabled(forA.named("Select in Explorer")))
        robot.interact { forA.named("Show History")?.fire() }
        assertEquals(listOf(a), shown)
        val forB = items("b.txt")
        assertTrue(disabled(forB.named("Show History")))
        assertFalse(disabled(forB.named("Select in Explorer")))
        // Acts on the tab's file, not the selected tab (the terminal).
        robot.interact { forB.named("Select in Explorer")?.fire() }
        assertEquals(listOf(b), selected)
        val forTerminal = items("Terminal 1")
        assertNull(forTerminal.named("Show History"), "not for tabs without a file")
        assertNull(forTerminal.named("Select in Explorer"), "not for tabs without a file")

        // One separator before the items offered, none without any.
        assertEquals(10, forA.size)
        editor.tabMenu.showHistory = null
        assertEquals(9, items("a.txt").size)
        editor.tabMenu.selectInExplorer = null
        assertEquals(7, items("a.txt").size)
    }

    @Test
    fun `selectedFile is the selected tab's file, none for other tabs, and follows a moved file`(robot: FxRobot) {
        val a = textFile("a.txt")
        val b = textFile("b.txt")
        open(robot, a, b)
        val other = WarningTab("Terminal 1", null)
        robot.interact { editor.open(other) }

        // Observed, as the window's menu and bar do: the value is then kept, not read afresh.
        val seen = java.util.concurrent.CopyOnWriteArrayList<java.nio.file.Path?>()
        robot.interact { editor.selectedFile.subscribe { path -> seen.add(path) } }

        fun selectedFile() = WaitForAsyncUtils.asyncFx<java.nio.file.Path?> { editor.selectedFile.value }.get()

        assertNull(selectedFile())
        robot.interact { editor.select(editor.tabs.single { it.title.value == "b.txt" }) }
        assertEquals(b, selectedFile())

        val moved = dir.resolve("c.txt")
        move(robot, b, moved)
        assertEquals(moved, selectedFile())
        assertEquals(moved, seen.last())
    }
}
