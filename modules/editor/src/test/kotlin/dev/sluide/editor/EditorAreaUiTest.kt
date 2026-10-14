package dev.sluide.editor

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.PythonFileViewer
import dev.sluide.fileviewers.TextDiff
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import dev.sluide.settings.FontStyle
import dev.sluide.settings.SettingsStore
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Renders the real editor area in a stage. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EditorAreaUiTest {
    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var settingsDir: Path

    private lateinit var stage: Stage
    private lateinit var editor: EditorArea
    private lateinit var fontSettings: FontSettings
    private val watcher = FileWatcher(Platform::runLater, 50.milliseconds)

    @AfterEach
    fun closeWatcher() = watcher.close()

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        val fallback = DefaultTextFileViewer()
        fontSettings =
            FontSettings(SettingsStore(settingsDir.resolve("settings.properties")), FontSettings.systemDefaults())
        editor = EditorArea(FileViewers(listOf(fallback, PythonFileViewer()), fallback), watcher, fontSettings)
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

    /** The text of the shown file view, unless it is a diff. */
    private fun shownText(robot: FxRobot): String? =
        robot
            .lookup(".editor-content .code-editor .code-area")
            .queryAllAs(CodeArea::class.java)
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
            fireMouse(node, type, button, buttonDown = type == MouseEvent.MOUSE_PRESSED)
        }
    }

    /** Fires a mouse event at [node] as if the pointer were at [sceneX], [sceneY], without OS input. */
    private fun fireMouse(
        node: Node,
        type: EventType<MouseEvent>,
        button: MouseButton = MouseButton.PRIMARY,
        sceneX: Double = 1.0,
        sceneY: Double = 1.0,
        buttonDown: Boolean = true,
    ) {
        Event.fireEvent(
            node,
            MouseEvent(
                type,
                sceneX,
                sceneY,
                sceneX,
                sceneY,
                button,
                1,
                false,
                false,
                false,
                false,
                buttonDown && button == MouseButton.PRIMARY,
                buttonDown && button == MouseButton.MIDDLE,
                false,
                false,
                false,
                true,
                null,
            ),
        )
    }

    /**
     * Presses the primary button on the middle of [node] and moves the mouse by [dx] in small steps, then releases it
     * unless [release] is `false`; the events are fired at [node].
     */
    private fun dragBy(
        robot: FxRobot,
        node: Node,
        dx: Double,
        release: Boolean = true,
    ) {
        val bounds = node.layoutBounds
        val start = node.localToScene(bounds.centerX, bounds.centerY)
        val steps = (abs(dx) / 5).toInt().coerceAtLeast(1)
        robot.interact {
            fireMouse(node, MouseEvent.MOUSE_PRESSED, sceneX = start.x, sceneY = start.y)
            for (step in 1..steps) {
                fireMouse(node, MouseEvent.MOUSE_DRAGGED, sceneX = start.x + dx * step / steps, sceneY = start.y)
            }
        }
        WaitForAsyncUtils.waitForFxEvents()
        if (release) releaseOn(robot, node, start.x + dx)
    }

    private fun releaseOn(
        robot: FxRobot,
        node: Node,
        sceneX: Double,
    ) {
        robot.interact { fireMouse(node, MouseEvent.MOUSE_RELEASED, sceneX = sceneX, buttonDown = false) }
        WaitForAsyncUtils.waitForFxEvents()
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

    /** Sends a context menu request through the scene graph, like a right-click, without OS input. */
    private fun requestContextMenu(node: Node) {
        val bounds = node.localToScreen(node.boundsInLocal)
        Event.fireEvent(
            node,
            ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                1.0,
                1.0,
                bounds.minX + 1,
                bounds.minY + 1,
                false,
                null,
            ),
        )
    }

    @Test
    fun `an edit on disk reloads the shown content`(robot: FxRobot) {
        val a = textFile("a.txt", "first")
        open(robot, a)

        a.writeText("second")

        eventually(robot) { assertEquals("second", shownText(robot)) }
        assertEquals(a, editor.selectedPath)
    }

    /** The text area of the shown file view, unless it is a diff. */
    private fun shownArea(robot: FxRobot): CodeArea =
        robot.lookup(".editor-content .code-editor .code-area").queryAs(CodeArea::class.java)

    @Test
    fun `the tab of a file with unsaved changes shows a dot until it is saved`(robot: FxRobot) {
        val a = textFile("a.txt")
        open(robot, a)

        robot.interact { shownArea(robot).insertText(0, "edited ") }
        assertEquals(listOf("● a.txt"), tabTitles(robot))

        robot.interact { (checkNotNull(editor.editorTabs.find(a)).view as EditableFileView).save() }
        assertEquals(listOf("a.txt"), tabTitles(robot))
    }

    @Test
    fun `save and saveAll save the edited files`(robot: FxRobot) {
        val a = textFile("a.txt", "a")
        val b = textFile("b.txt", "b")
        open(robot, a)
        robot.interact { shownArea(robot).appendText("1") }
        open(robot, b)
        robot.interact { shownArea(robot).appendText("2") }

        robot.interact { assertTrue(editor.save()) }
        assertEquals(listOf("a", "b2"), listOf(a.readText(), b.readText()))

        robot.interact { assertTrue(editor.saveAll()) }
        assertEquals(listOf("a1", "b2"), listOf(a.readText(), b.readText()))
        assertEquals(listOf("a.txt", "b.txt"), tabTitles(robot))
    }

    /** Opens `.unsavable` files in an edited view whose saving always fails. */
    private class UnsavableViewer : FileViewer {
        override fun canOpen(file: Path) = file.toString().endsWith(".unsavable")

        override fun open(file: Path): FileView =
            object : EditableFileView {
                override val node = Label("edited")
                override val dirty = SimpleBooleanProperty(true)

                override fun reload() {}

                override fun save() = throw IOException("Disk full")
            }
    }

    @Test
    fun `save all saves the other files when one cannot be saved`(robot: FxRobot) {
        val fallback = DefaultTextFileViewer()
        val area = EditorArea(FileViewers(listOf(fallback, UnsavableViewer()), fallback), watcher, fontSettings)
        val errors = mutableListOf<String>()
        area.showError = { errors += it }
        robot.interact { stage.scene = Scene(area, 800.0, 400.0) }
        val a = textFile("a.txt", "a")
        val b = textFile("b.txt", "b")
        robot.interact {
            area.open(a)
            area.open(textFile("notes.unsavable"))
            area.open(b)
        }
        for (file in listOf(a, b)) {
            robot.interact {
                area.open(file)
                robot.lookup(".editor-content .code-area").queryAs(CodeArea::class.java).appendText("1")
            }
        }

        robot.interact { assertFalse(area.saveAll()) }

        assertEquals(listOf("a1", "b1"), listOf(a.readText(), b.readText()))
        assertEquals(listOf("notes.unsavable could not be saved. Disk full"), errors)
        assertEquals(listOf("a.txt", "\u25cf notes.unsavable", "b.txt"), area.tabs.map { it.title.value })
    }

    @Test
    fun `a file that cannot be saved stays edited and the error is shown`(robot: FxRobot) {
        val fallback = DefaultTextFileViewer()
        val area = EditorArea(FileViewers(listOf(fallback, UnsavableViewer()), fallback), watcher, fontSettings)
        val errors = mutableListOf<String>()
        area.showError = { errors += it }
        robot.interact { stage.scene = Scene(area, 800.0, 400.0) }
        val file = textFile("notes.unsavable")
        robot.interact { area.open(file) }

        robot.interact { assertFalse(area.save()) }
        robot.interact { assertFalse(area.saveAll()) }

        assertEquals(List(2) { "notes.unsavable could not be saved. Disk full" }, errors)
        assertEquals(
            "● notes.unsavable",
            area.tabs
                .single()
                .title.value,
        )
    }

    @Test
    fun `an open editor is not swapped for a diff, not even after saving`(robot: FxRobot) {
        val a = textFile("a.txt", "new\n")
        open(robot, a)
        robot.interact { shownArea(robot).insertText(0, "newer ") }

        editor.diffOf = { TextDiff("old\n", listOf(LineChange(0..0, 0..0))) }
        robot.interact { editor.refreshDiffs() }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals("newer new\n", shownText(robot))
        assertEquals(emptyList(), shownDiff(robot))

        robot.interact {
            editor.save()
            editor.refreshDiffs()
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals("newer new\n", shownText(robot))
        assertEquals(emptyList(), shownDiff(robot))
    }

    /** The texts of the code areas of the shown diff, left to right; empty if no diff is shown. */
    private fun shownDiff(robot: FxRobot): List<String> =
        robot
            .lookup(".editor-content .diff-view .code-area")
            .queryAllAs(CodeArea::class.java)
            .sortedBy { it.localToScene(0.0, 0.0).x }
            .map { it.text }

    @Test
    fun `files with a diff open side by side and switch to the editor when the diff goes, not back`(robot: FxRobot) {
        val a = textFile("a.txt", "new\n")
        var diff: TextDiff? = TextDiff("old\n", listOf(LineChange(0..0, 0..0)))
        editor.diffOf = { diff }
        open(robot, a)
        val tab = editor.selectedTab

        assertEquals(listOf("old", "new"), shownDiff(robot))
        assertNull(shownText(robot))

        diff = null
        robot.interact { editor.refreshDiffs() }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), shownDiff(robot))
        assertEquals("new\n", shownText(robot))

        // Until diffs are shown on demand, an open editor stays (ADR 0020).
        diff = TextDiff("older\n", listOf(LineChange(0..0, 0..0)))
        robot.interact { editor.refreshDiffs() }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), shownDiff(robot))
        assertEquals("new\n", shownText(robot))
        assertSame(tab, editor.selectedTab)
        assertEquals(listOf(a), editor.openPaths)
    }

    @Test
    fun `an edit on disk asks for the diff again`(robot: FxRobot) {
        val a = textFile("a.txt", "one\n")
        editor.diffOf = { path ->
            val changed = path.toFile().readText() != "one\n"
            TextDiff("one\n", if (changed) listOf(LineChange(0..0, 0..0)) else emptyList())
        }
        open(robot, a)
        assertEquals(listOf("one", "one"), shownDiff(robot))

        a.writeText("two\n")

        eventually(robot) { assertEquals(listOf("one", "two"), shownDiff(robot)) }
    }

    @Test
    fun `deleting a file on disk closes its tab`(robot: FxRobot) {
        val a = textFile("a.txt")
        val b = textFile("b.txt")
        open(robot, a, b)

        b.deleteExisting()

        eventually(robot) { assertEquals(listOf("a.txt"), tabTitles(robot)) }
        assertEquals("content of a.txt", shownText(robot))
    }

    @Test
    fun `a file that became binary is closed`(robot: FxRobot) {
        val a = textFile("a.txt")
        open(robot, a)

        a.writeBytes(byteArrayOf(0x00, 0x01, 0x02))

        eventually(robot) { assertEquals(emptyList(), tabTitles(robot)) }
    }

    @Test
    fun `closing tabs ends their watches`(robot: FxRobot) {
        val files = listOf("a.txt", "b.txt", "c.txt").map { textFile(it) }
        open(robot, *files.toTypedArray())
        assertEquals(3, editor.watchCount)

        robot.interact { editor.editorTabs.closeOthers(checkNotNull(editor.editorTabs.find(files[0]))) }
        assertEquals(1, editor.watchCount)

        robot.interact { editor.closeAll() }
        assertEquals(0, editor.watchCount)

        files[0].writeText("changed after closing")
        Thread.sleep(300)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), tabTitles(robot))
    }

    @Test
    fun `an open file keeps reloading after its folder was deleted and recreated`(robot: FxRobot) {
        val folder = dir.resolve("gen").createDirectories()
        val file = folder.resolve("out.txt").apply { writeText("v1") }
        open(robot, file)

        folder.toFile().deleteRecursively()
        folder.createDirectories()
        file.writeText("v2")
        eventually(robot) { assertEquals("v2", shownText(robot)) }

        file.writeText("v3")
        eventually(robot) { assertEquals("v3", shownText(robot)) }
    }

    @Test
    fun `other tabs show their content, follow title changes and are disposed on close`(robot: FxRobot) {
        val a = textFile("a.txt")
        open(robot, a)
        var disposed = false
        val shellTitle = SimpleStringProperty("Terminal 1")
        val tab =
            object : EditorTab {
                override val title = shellTitle
                override val tooltip = "a terminal"
                override val content = Label("terminal content").apply { styleClass += "other-content" }

                override fun dispose() {
                    disposed = true
                }
            }

        robot.interact { editor.open(tab) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("a.txt", "Terminal 1"), tabTitles(robot))
        assertSame(tab, editor.selectedTab)
        assertNull(editor.selectedPath)
        assertEquals(listOf(a), editor.openPaths)
        assertTrue(robot.lookup(".editor-content .other-content").tryQuery<Node>().isPresent)

        robot.interact { shellTitle.set("vim README.md") }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("a.txt", "vim README.md"), tabTitles(robot))

        robot.interact { editor.closeAll() }
        assertTrue(disposed)
        assertEquals(0, editor.watchCount)
    }

    private class WarningTab(
        name: String,
        override var closeWarning: String?,
    ) : EditorTab {
        override val title = SimpleStringProperty(name)
        override val tooltip = name
        override val content = Label(name)
    }

    @Test
    fun `closing a tab with a warning asks first and keeps it on cancel`(robot: FxRobot) {
        val asked = mutableListOf<List<String>>()
        var answer = false
        editor.confirmClose = { warnings ->
            asked += warnings
            answer
        }
        val busy = WarningTab("Terminal 1", "Terminal 1 is still running java.")
        robot.interact { editor.open(busy) }
        WaitForAsyncUtils.waitForFxEvents()
        val close = tabLabel(robot, "Terminal 1").parent.lookup(".close-button") as Button

        robot.interact { close.fire() }
        assertEquals(listOf(listOf("Terminal 1 is still running java.")), asked)
        assertEquals(listOf("Terminal 1"), tabTitles(robot))

        answer = true
        robot.interact { close.fire() }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), tabTitles(robot))
    }

    @Test
    fun `tabs without a warning close without asking, close all asks once, closing the window does not`(
        robot: FxRobot,
    ) {
        val asked = mutableListOf<List<String>>()
        editor.confirmClose = { warnings ->
            asked += warnings
            false
        }
        val idle = WarningTab("Terminal 1", null)
        val busy1 = WarningTab("Terminal 2", "Terminal 2 is still running vim.")
        val busy2 = WarningTab("Terminal 3", "Terminal 3 is still running sleep.")
        val a = textFile("a.txt")
        open(robot, a)
        robot.interact { listOf(idle, busy1, busy2).forEach(editor::open) }

        robot.interact {
            editor.tabStrip
                .contextMenuOf(busy2)
                .items
                .first { it.text == "Close All" }
                .fire()
        }
        assertEquals(listOf(listOf("Terminal 2 is still running vim.", "Terminal 3 is still running sleep.")), asked)
        assertEquals(listOf("a.txt", "Terminal 1", "Terminal 2", "Terminal 3"), tabTitles(robot))

        robot.interact {
            editor.tabStrip
                .contextMenuOf(
                    busy1,
                ).items
                .first { it.text == "Close Tabs to the Left" }
                .fire()
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("Terminal 2", "Terminal 3"), tabTitles(robot))
        assertEquals(1, asked.size, "nothing to confirm for the file and the idle terminal")

        busy1.closeWarning = null
        robot.interact { editor.closeAll() }
        assertEquals(emptyList(), tabTitles(robot))
        assertEquals(1, asked.size, "closing all with the window does not ask")
    }

    @Test
    fun `open text and code views use the editor font and follow its changes`(robot: FxRobot) {
        val text = textFile("notes.txt")
        val code = textFile("main.py", "print(1)")
        open(robot, text, code)
        val serif = FontSpec("Serif", 21.0, FontStyle.ITALIC)

        robot.interact { fontSettings.apply(mapOf(FontArea.EDITOR to serif)) }
        WaitForAsyncUtils.waitForFxEvents()

        val codeText =
            robot
                .lookup(".editor-content .code-area .text")
                .queryAllAs(javafx.scene.text.Text::class.java)
                .first { it.text.contains("print") }
        assertEquals(21.0, codeText.font.size, "code view text inherits the size")
        assertEquals("Serif", codeText.font.family)
        robot.interact { editor.editorTabs.select(checkNotNull(editor.editorTabs.find(text))) }
        WaitForAsyncUtils.waitForFxEvents()
        val plainText =
            robot
                .lookup(".editor-content .code-area .text")
                .queryAllAs(javafx.scene.text.Text::class.java)
                .first { it.text.contains("content") }
        assertEquals(21.0, plainText.font.size)
        assertTrue("Italic" in plainText.font.style || "Oblique" in plainText.font.style, plainText.font.style)

        val closed = checkNotNull(editor.editorTabs.find(text))
        robot.interact { editor.editorTabs.close(closed) }
        assertFalse(closed.content.styleProperty().isBound, "closed views are unbound")
    }

    /** Repeats [assertion] on the FX thread until it passes; the JDK may poll for file changes on macOS. */
    private fun eventually(
        robot: FxRobot,
        assertion: () -> Unit,
    ) {
        val deadline = System.nanoTime() + 20.seconds.inWholeNanoseconds
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            var failure: AssertionError? = null
            robot.interact {
                try {
                    assertion()
                } catch (e: AssertionError) {
                    failure = e
                }
            }
            val error = failure ?: return
            if (System.nanoTime() > deadline) throw error
            Thread.sleep(50)
        }
    }

    /** Opens `.link` files with a view that remembers the host the editor area gives it. */
    private class HostRecordingViewer : FileViewer {
        val views = mutableListOf<FileView>()

        override fun canOpen(file: Path) = file.toString().endsWith(".link")

        override fun open(file: Path): FileView =
            object : FileView {
                override val node = Label(file.fileName.toString())
                override var host: FileViewHost? = null

                override fun reload() {}
            }.also { views += it }
    }

    @Test
    fun `file views can open files and web pages through their host`(robot: FxRobot) {
        val viewer = HostRecordingViewer()
        val fallback = DefaultTextFileViewer()
        val area = EditorArea(FileViewers(listOf(fallback, viewer), fallback), watcher, fontSettings)
        val opened = mutableListOf<URI>()
        area.openWebPage = { opened += it }
        area.projectFolder = { dir }
        robot.interact { stage.scene = Scene(area, 800.0, 400.0) }
        val other = textFile("other.txt")

        robot.interact { area.open(textFile("readme.link")) }
        val host = assertNotNull(viewer.views.single().host)
        robot.interact { assertTrue(host.openFile(other)) }
        host.openWebPage(URI("https://example.com"))

        assertEquals(other, area.selectedPath)
        assertEquals(listOf(URI("https://example.com")), opened)
        assertEquals(dir, host.projectFolder)
    }
}
