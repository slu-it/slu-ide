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
import javafx.scene.control.TextField
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
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
import kotlin.io.path.exists
import kotlin.io.path.moveTo
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
            // The editor stays below a diff shown over it, hidden.
            .singleOrNull { area -> generateSequence<Node>(area) { it.parent }.all { it.isVisible } }
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

    /** The change markers in the gutter of the shown editor by line number; lines without a marker are left out. */
    private fun shownMarkers(robot: FxRobot): Map<Int, String> {
        WaitForAsyncUtils.waitForFxEvents()
        return robot
            .lookup(".editor-content .code-editor .gutter")
            .queryAllAs(HBox::class.java)
            .mapNotNull { row ->
                val line = (row.children[0] as Label).text.trim().toInt()
                row.children[1]
                    .styleClass
                    .singleOrNull { it != "change-marker" }
                    ?.let { line to it }
            }.toMap()
    }

    /** The texts of the code areas of the shown diff, left to right; empty if no diff is shown. */
    private fun shownDiff(robot: FxRobot): List<String> =
        robot
            .lookup(".editor-content .diff-view .code-area")
            .queryAllAs(CodeArea::class.java)
            .sortedBy { it.localToScene(0.0, 0.0).x }
            .map { it.text }

    @Test
    fun `files with a diff open in the editor with their changes marked, following the diff`(robot: FxRobot) {
        val a = textFile("a.txt", "new\nsame\n")
        var diff: TextDiff? = TextDiff("old\nsame\n", listOf(LineChange(0..0, 0..0)))
        editor.diffOf = { diff }
        open(robot, a)
        val tab = editor.selectedTab

        assertEquals("new\nsame\n", shownText(robot))
        assertEquals(emptyList(), shownDiff(robot))
        assertEquals(mapOf(1 to "diff-modified"), shownMarkers(robot))

        diff = null
        robot.interact { editor.refreshDiffs() }
        assertEquals(emptyMap(), shownMarkers(robot))

        diff = TextDiff("new\n", listOf(LineChange(1 until 1, 1..1)))
        robot.interact { editor.refreshDiffs() }
        assertEquals(mapOf(2 to "diff-added"), shownMarkers(robot))
        assertEquals(emptyList(), shownDiff(robot))
        assertSame(tab, editor.selectedTab)
        assertEquals(listOf(a), editor.openPaths)
    }

    private fun pressEscape(
        robot: FxRobot,
        target: Node,
    ) = robot.interact {
        Event.fireEvent(target, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false))
    }

    @Test
    fun `the diff shows on demand over the editor, which keeps its unsaved changes, caret and undo`(robot: FxRobot) {
        val a = textFile("a.txt", "new\n")
        editor.diffOf = { TextDiff("old\n", listOf(LineChange(0..0, 0..0)), "HEAD", "Working Tree") }
        open(robot, a)
        val area = shownArea(robot)
        robot.interact {
            area.requestFocus()
            area.insertText(0, "edited ")
        }
        val caret = area.caretPosition
        assertEquals(true, editor.canToggleDiff.value)

        robot.interact { editor.toggleDiff() }
        assertEquals(listOf("old", "new"), shownDiff(robot), "the saved file")
        assertNull(shownText(robot), "the editor is hidden")
        val titles =
            robot
                .lookup(".diff-title")
                .queryAllAs(Label::class.java)
                .map { it.text }
                .toSet()
        assertEquals(setOf("HEAD", "Working Tree (saved)"), titles)
        assertEquals(listOf("● a.txt"), tabTitles(robot))

        pressEscape(robot, robot.lookup(".editor-content .diff-view").query())
        assertEquals(emptyList(), shownDiff(robot))
        assertSame(area, shownArea(robot))
        assertEquals("edited new\n", area.text)
        assertEquals(caret, area.caretPosition)
        assertSame(area, stage.scene.focusOwner)
        robot.interact { area.undo() }
        assertEquals("new\n", area.text)

        robot.interact { editor.toggleDiff() }
        val titlesWhenClean =
            robot
                .lookup(".diff-title")
                .queryAllAs(Label::class.java)
                .map { it.text }
                .toSet()
        assertEquals(setOf("HEAD", "Working Tree"), titlesWhenClean)
        robot.interact { editor.toggleDiff() }
        assertEquals(emptyList(), shownDiff(robot))
    }

    @Test
    fun `clicking a change marker shows the diff at its change`(robot: FxRobot) {
        val lines = (1..80).map { "line $it" }
        val a = textFile("a.txt", lines.joinToString("\n", postfix = "\n"))
        val original = lines.toMutableList().apply { set(59, "old line 60") }.joinToString("\n", postfix = "\n")
        editor.diffOf = { TextDiff(original, listOf(LineChange(59..59, 59..59))) }
        open(robot, a)
        robot.interact { shownArea(robot).showParagraphAtTop(50) }
        WaitForAsyncUtils.waitForFxEvents()

        val marker =
            robot
                .lookup(".editor-content .gutter")
                .queryAllAs(HBox::class.java)
                .single { (it.children[0] as Label).text.trim() == "60" }
                .children[1]
        for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            robot.interact { fireMouse(marker, type) }
        }
        WaitForAsyncUtils.waitForFxEvents()

        val right =
            robot
                .lookup(".editor-content .diff-view .code-area")
                .queryAllAs(CodeArea::class.java)
                .maxBy { it.localToScene(0.0, 0.0).x }
        assertEquals(59, right.currentParagraph)
        assertTrue(right.firstVisibleParToAllParIndex() in 50..59, "shown near the top")
    }

    @Test
    fun `the Edit button of the diff goes back to the editor`(robot: FxRobot) {
        editor.diffOf = { TextDiff("old\n", listOf(LineChange(0..0, 0..0))) }
        open(robot, textFile("a.txt", "new\n"))
        robot.interact { editor.toggleDiff() }

        robot.interact {
            robot
                .lookup(".diff-toolbar .button")
                .queryAllAs(Button::class.java)
                .single { it.text == "Edit" }
                .fire()
        }

        assertEquals(emptyList(), shownDiff(robot))
        assertEquals("new\n", shownText(robot))
    }

    /** Puts a text field above the editor area, to have the focus outside of it. */
    private fun fieldAbove(robot: FxRobot): TextField {
        val field = TextField()
        robot.interact {
            stage.scene.root = Pane()
            stage.scene = Scene(VBox(field, editor), 800.0, 400.0)
        }
        return field
    }

    @Test
    fun `closing the diff gives its focus to the text, also when the editor had none`(robot: FxRobot) {
        val field = fieldAbove(robot)
        editor.diffOf = { TextDiff("old\n", listOf(LineChange(0..0, 0..0))) }
        open(robot, textFile("a.txt", "new\n"))
        robot.interact {
            field.requestFocus()
            editor.toggleDiff()
        }
        val diff = robot.lookup(".editor-content .diff-view").query<Node>()
        assertTrue(stage.scene.focusOwner.isInside(diff), "the diff has the focus")

        pressEscape(robot, diff)

        assertSame(shownArea(robot), stage.scene.focusOwner)
    }

    @Test
    fun `a diff going away leaves the focus elsewhere alone`(robot: FxRobot) {
        val field = fieldAbove(robot)
        var diff: TextDiff? = TextDiff("old\n", listOf(LineChange(0..0, 0..0)))
        editor.diffOf = { diff }
        open(robot, textFile("a.txt", "new\n"))
        robot.interact { editor.toggleDiff() }
        robot.interact { field.requestFocus() }

        diff = null
        robot.interact { editor.refreshDiffs() }

        assertEquals(emptyList(), shownDiff(robot))
        assertSame(field, stage.scene.focusOwner)
    }

    private fun Node.isInside(ancestor: Node) = generateSequence(this) { it.parent }.any { it === ancestor }

    @Test
    fun `closing a tab with unsaved changes while its diff is shown asks to save`(robot: FxRobot) {
        val a = textFile("a.txt", "a\n")
        editor.diffOf = { TextDiff("old\n", listOf(LineChange(0..0, 0..0))) }
        val asked = openEdited(robot, a) { SaveChoice.SAVE }
        robot.interact { editor.toggleDiff() }
        val close = tabLabel(robot, "● a.txt").parent.lookup(".close-button") as Button

        robot.interact { close.fire() }

        assertEquals(listOf(listOf("a.txt")), asked)
        assertEquals("edited a\n", a.readText())
        assertEquals(emptyList(), tabTitles(robot))
    }

    @Test
    fun `Save while the diff is shown saves the editor's text`(robot: FxRobot) {
        val a = textFile("a.txt", "a\n")
        editor.diffOf = { TextDiff("old\n", listOf(LineChange(0..0, 0..0))) }
        openEdited(robot, a) { SaveChoice.CANCEL }
        robot.interact { editor.toggleDiff() }

        robot.interact { assertTrue(editor.save()) }

        assertEquals("edited a\n", a.readText())
        assertEquals(listOf("a.txt"), tabTitles(robot))
    }

    @Test
    fun `files without a diff have none to show`(robot: FxRobot) {
        open(robot, textFile("a.txt", "new\n"))
        assertEquals(false, editor.canToggleDiff.value)

        robot.interact { editor.toggleDiff() }

        assertEquals(emptyList(), shownDiff(robot))
        assertEquals("new\n", shownText(robot))
    }

    @Test
    fun `a shown diff follows the file and gives way to the editor when it goes`(robot: FxRobot) {
        val a = textFile("a.txt", "one\n")
        var diff: TextDiff? = TextDiff("zero\n", listOf(LineChange(0..0, 0..0)))
        editor.diffOf = { diff }
        open(robot, a)
        robot.interact { editor.toggleDiff() }

        a.writeText("two\n")
        eventually(robot) { assertEquals(listOf("zero", "two"), shownDiff(robot)) }

        diff = null
        robot.interact { editor.refreshDiffs() }
        assertEquals(emptyList(), shownDiff(robot))
        assertEquals("two\n", shownText(robot))
        assertEquals(false, editor.canToggleDiff.value)
    }

    @Test
    fun `lines edited apart stay apart when the diffs are asked for again`(robot: FxRobot) {
        val lines = (1..300).map { "line $it" }
        val a = textFile("a.txt", lines.joinToString("\n", postfix = "\n"))
        val diff = TextDiff(lines.joinToString("\n", prefix = "old\n", postfix = "\n"), listOf(LineChange(0..0, 0..0)))
        editor.diffOf = { diff }
        open(robot, a)
        val area = shownArea(robot)
        robot.interact {
            area.insertText(area.getAbsolutePosition(1, 0), "edited ")
            area.insertText(area.getAbsolutePosition(299, 0), "edited ")
            editor.refreshDiffs()
        }

        assertEquals(mapOf(1 to "diff-modified", 2 to "diff-modified"), shownMarkers(robot))
        robot.interact { area.showParagraphAtBottom(300) }
        assertEquals(mapOf(300 to "diff-modified"), shownMarkers(robot))
    }

    @Test
    fun `only files shown as text are asked for their diff`(robot: FxRobot) {
        val fallback = DefaultTextFileViewer()
        val area = EditorArea(FileViewers(listOf(fallback, UnsavableViewer()), fallback), watcher, fontSettings)
        val asked = mutableListOf<String>()
        area.diffOf = { path ->
            asked += path.name
            null
        }
        val notes = textFile("notes.unsavable")
        val a = textFile("a.txt")
        robot.interact {
            area.open(notes)
            area.open(a)
            area.refreshDiffs()
        }
        // Changes on disk may ask again.
        assertEquals(setOf("a.txt"), asked.toSet())
    }

    @Test
    fun `saving keeps the editor and its changes come from the diff again`(robot: FxRobot) {
        val a = textFile("a.txt", "new\n")
        editor.diffOf = { path ->
            if (path.readText() == "new\n") null else TextDiff("new\n", listOf(LineChange(0..0, 0..0)))
        }
        open(robot, a)
        robot.interact { shownArea(robot).insertText(0, "newer ") }
        assertEquals(mapOf(1 to "diff-modified"), shownMarkers(robot))

        robot.interact { editor.save() }

        eventually(robot) { assertEquals("newer new\n", a.readText()) }
        robot.interact { editor.refreshDiffs() }
        assertEquals("newer new\n", shownText(robot))
        assertEquals(mapOf(1 to "diff-modified"), shownMarkers(robot))
        assertEquals(emptyList(), shownDiff(robot))
    }

    @Test
    fun `an edit on disk asks for the diff again`(robot: FxRobot) {
        val a = textFile("a.txt", "one\n")
        editor.diffOf = { path ->
            // A line removed before the new text, unlike the edit from one to two.
            if (path.readText() == "one\n") null else TextDiff("zero\ntwo\n", listOf(LineChange(0..0, 0 until 0)))
        }
        open(robot, a)
        assertEquals(emptyMap(), shownMarkers(robot))

        a.writeText("two\n")

        eventually(robot) {
            assertEquals("two\n", shownText(robot))
            assertEquals(mapOf(1 to "diff-removed-above"), shownMarkers(robot))
        }
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

    /** The text of the banner shown above the selected file's text, if any. */
    private fun bannerText(robot: FxRobot): String? =
        robot
            .lookup(".editor-content .editor-banner .label")
            .queryAllAs(Label::class.java)
            .singleOrNull()
            ?.text

    @Test
    fun `a deleted file with unsaved changes keeps its tab, and saving recreates it`(robot: FxRobot) {
        val a = textFile("a.txt", "a")
        open(robot, a)
        robot.interact { shownArea(robot).appendText("1") }

        a.deleteExisting()

        eventually(robot) { assertEquals("a.txt was deleted on disk; saving recreates it.", bannerText(robot)) }
        assertEquals(listOf("\u25cf a.txt"), tabTitles(robot))

        robot.interact { assertTrue(editor.save()) }
        assertEquals("a1", a.readText())
        assertEquals(listOf("a.txt"), tabTitles(robot))
        eventually(robot) { assertNull(bannerText(robot)) }
    }

    @Test
    fun `saving a file whose folder was deleted shows why it failed`(robot: FxRobot) {
        val folder = dir.resolve("gen").createDirectories()
        val file = folder.resolve("out.txt").apply { writeText("v1") }
        val errors = mutableListOf<String>()
        editor.showError = { errors += it }
        open(robot, file)
        robot.interact { shownArea(robot).appendText("2") }

        folder.toFile().deleteRecursively()
        eventually(robot) { assertNotNull(bannerText(robot)) }

        robot.interact { assertFalse(editor.save()) }
        assertEquals(listOf("out.txt could not be saved. Its folder does not exist."), errors)
        assertEquals(listOf("\u25cf out.txt"), tabTitles(robot))
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

    /** Moves [from] to [to] on disk and tells the editor area, in one go on the FX thread, as the explorer does. */
    private fun move(
        robot: FxRobot,
        from: Path,
        to: Path,
    ) {
        robot.interact {
            from.moveTo(to)
            editor.moved(from, to)
        }
    }

    @Test
    fun `a renamed file keeps its tab, which follows it to the new path`(robot: FxRobot) {
        val a = textFile("a.txt", "new\n")
        val b = textFile("b.txt")
        editor.diffOf = { if (it == a) TextDiff("old\n", listOf(LineChange(0..0, 0..0))) else null }
        open(robot, a, b)
        val tab = checkNotNull(editor.editorTabs.find(a))
        robot.interact {
            editor.editorTabs.select(tab)
            editor.toggleDiff()
        }
        val renamed = dir.resolve("renamed.txt")

        move(robot, a, renamed)

        assertEquals(listOf(renamed, b), editor.openPaths)
        assertSame(tab, editor.editorTabs.find(renamed), "the same tab and view")
        assertEquals(listOf("renamed.txt", "b.txt"), tabTitles(robot))
        assertEquals(renamed.toString(), editor.tabStrip.tooltipOf(tab).text)
        assertEquals(emptyList(), shownDiff(robot), "the diff of the old path is gone, as is the new path's")
        assertEquals(false, editor.canToggleDiff.value)
        assertEquals(2, editor.watchCount)
        // Reloaded after the old path's deletion was reported, which comes first.
        renamed.writeText("changed")
        eventually(robot) { assertEquals("changed", shownText(robot)) }
        assertEquals(listOf("renamed.txt", "b.txt"), tabTitles(robot), "the old path's deletion closes nothing")
    }

    @Test
    fun `a renamed file with unsaved changes stays edited and is saved at its new path`(robot: FxRobot) {
        val a = textFile("a.txt", "a")
        val b = textFile("b.txt", "b")
        open(robot, a)
        robot.interact { shownArea(robot).appendText("1") }
        open(robot, b)
        val renamed = dir.resolve("renamed.txt")

        move(robot, a, renamed)
        // Reloaded after the old path's deletion was reported: the same folder, so the same or a later batch.
        b.writeText("changed")
        eventually(robot) { assertEquals("changed", shownText(robot)) }

        assertEquals(listOf("\u25cf renamed.txt", "b.txt"), tabTitles(robot))
        robot.interact { editor.editorTabs.select(checkNotNull(editor.editorTabs.find(renamed))) }
        assertNull(bannerText(robot), "not deleted")
        robot.interact { assertTrue(editor.save()) }
        assertEquals("a1", renamed.readText())
        assertFalse(a.exists(), "nothing is written at the old path")
        assertEquals(listOf("renamed.txt", "b.txt"), tabTitles(robot))
    }

    @Test
    fun `renaming a folder moves the tabs of the files in it`(robot: FxRobot) {
        val folder = dir.resolve("src").createDirectories()
        val inner =
            folder
                .resolve("x")
                .createDirectories()
                .resolve("inner.txt")
                .apply { writeText("inner") }
        val top = folder.resolve("top.txt").apply { writeText("top") }
        val other =
            dir
                .resolve("src2")
                .createDirectories()
                .resolve("other.txt")
                .apply { writeText("other") }
        open(robot, inner, top, other)
        robot.interact { editor.editorTabs.select(checkNotNull(editor.editorTabs.find(top))) }
        robot.interact { shownArea(robot).appendText("!") }
        val renamed = dir.resolve("lib")

        move(robot, folder, renamed)

        assertEquals(
            listOf(renamed.resolve("x/inner.txt"), renamed.resolve("top.txt"), other),
            editor.openPaths,
        )
        assertEquals(listOf("inner.txt", "\u25cf top.txt", "other.txt"), tabTitles(robot))
        assertEquals(renamed.resolve("top.txt"), editor.selectedPath)
        robot.interact { assertTrue(editor.save()) }
        assertEquals("top!", renamed.resolve("top.txt").readText())
        robot.interact { editor.editorTabs.select(editor.editorTabs.files.first()) }
        // The folder's watches follow it: the new paths reload, and nothing reported closes a tab.
        renamed.resolve("x/inner.txt").writeText("changed")
        eventually(robot) { assertEquals("changed", shownText(robot)) }
        assertEquals(listOf("inner.txt", "top.txt", "other.txt"), tabTitles(robot))
    }

    @Test
    fun `a file renamed to another type opens anew in its tab, unless it has unsaved changes`(robot: FxRobot) {
        val a = textFile("a.txt", "x = 1\n")
        val b = textFile("b.txt", "y = 2\n")
        val c = textFile("c.txt")
        open(robot, a, b, c)
        robot.interact { shownArea(robot).appendText("!") }
        robot.interact { editor.editorTabs.select(checkNotNull(editor.editorTabs.find(a))) }
        val oldTab = editor.selectedTab
        val bTab = editor.editorTabs.find(b)

        move(robot, a, dir.resolve("a.py"))
        move(robot, b, dir.resolve("b.md"))
        move(robot, c, dir.resolve("c.py"))

        assertEquals(listOf("a.py", "b.md", "c.py").map(dir::resolve), editor.openPaths)
        val aTab = checkNotNull(editor.editorTabs.find(dir.resolve("a.py")))
        assertTrue(aTab !== oldTab, "a new tab in its place")
        assertTrue(aTab.viewer is PythonFileViewer)
        assertSame(aTab, editor.selectedTab)
        assertEquals("x = 1\n", shownText(robot))
        assertSame(bTab, editor.editorTabs.find(dir.resolve("b.md")), "the same viewer shows it")
        val cTab = checkNotNull(editor.editorTabs.find(dir.resolve("c.py")))
        assertTrue(cTab.viewer is DefaultTextFileViewer, "unsaved changes keep their view")
        assertEquals(listOf("a.py", "b.md", "\u25cf c.py"), tabTitles(robot))
        assertEquals(3, editor.watchCount)
    }

    @Test
    fun `a file with unsaved changes renamed to the path of another tab keeps them at its old path`(robot: FxRobot) {
        val a = textFile("a.txt", "a")
        val b = textFile("b.txt", "b")
        open(robot, a)
        robot.interact { shownArea(robot).appendText("1") }
        a.deleteExisting()
        eventually(robot) { assertEquals("a.txt was deleted on disk; saving recreates it.", bannerText(robot)) }
        open(robot, b)
        robot.interact { shownArea(robot).appendText("2") }

        move(robot, b, a)

        assertEquals(listOf(a, b), editor.openPaths)
        assertEquals(listOf("\u25cf a.txt", "\u25cf b.txt"), tabTitles(robot))
        assertEquals("b.txt was deleted on disk; saving recreates it.", bannerText(robot))
        robot.interact { editor.editorTabs.select(checkNotNull(editor.editorTabs.find(a))) }
        assertEquals("a.txt changed on disk.", bannerText(robot))
    }

    @Test
    fun `a file renamed to the path of a deleted file with unsaved changes leaves that tab to it`(robot: FxRobot) {
        val a = textFile("a.txt", "a")
        val b = textFile("b.txt", "b")
        open(robot, a)
        robot.interact { shownArea(robot).appendText("1") }
        a.deleteExisting()
        eventually(robot) { assertEquals("a.txt was deleted on disk; saving recreates it.", bannerText(robot)) }
        open(robot, b)

        move(robot, b, a)

        assertEquals(listOf(a), editor.openPaths)
        assertEquals(listOf("\u25cf a.txt"), tabTitles(robot))
        assertEquals("a.txt changed on disk.", bannerText(robot), "at once, so that saving cannot overwrite it unseen")
    }

    @Test
    fun `files with unsaved changes are found at or below a path`(robot: FxRobot) {
        val folder = dir.resolve("src").createDirectories()
        val inner =
            folder
                .resolve("x")
                .createDirectories()
                .resolve("inner.txt")
                .apply { writeText("inner") }
        val top = folder.resolve("top.txt").apply { writeText("top") }
        val clean = folder.resolve("clean.txt").apply { writeText("clean") }
        val other =
            dir
                .resolve("src2")
                .createDirectories()
                .resolve("other.txt")
                .apply { writeText("other") }
        for (file in listOf(inner, top, other)) {
            open(robot, file)
            robot.interact { shownArea(robot).appendText("!") }
        }
        open(robot, clean)

        assertEquals(listOf(inner, top), editor.dirtyUnder(folder))
        assertEquals(listOf(top), editor.dirtyUnder(top))
        assertEquals(emptyList(), editor.dirtyUnder(clean))
        assertEquals(listOf(inner, top, other), editor.dirtyUnder(dir))
    }

    @Test
    fun `closing the tabs below a deleted path does not ask, but keeps changes made since`(robot: FxRobot) {
        val folder = dir.resolve("src").createDirectories()
        val inner = folder.resolve("inner.txt").apply { writeText("inner") }
        val clean = folder.resolve("clean.txt").apply { writeText("clean") }
        val later = folder.resolve("later.txt").apply { writeText("later") }
        val other =
            dir
                .resolve("src2")
                .createDirectories()
                .resolve("other.txt")
                .apply { writeText("other") }
        val terminal =
            object : EditorTab {
                override val title = SimpleStringProperty("Terminal")
                override val tooltip = "a terminal"
                override val content = Pane()
                override val closeWarning = "Terminal is still running."
            }
        editor.confirmSave = { error("asked to save $it") }
        editor.confirmClose = { error("asked to close $it") }
        open(robot, inner)
        robot.interact { shownArea(robot).appendText("!") }
        open(robot, clean, other)
        robot.interact { editor.open(terminal) }
        val discarded = editor.dirtyUnder(folder)
        // Edited after the delete was confirmed, while it ran.
        open(robot, later)
        robot.interact { shownArea(robot).appendText("!") }
        folder.toFile().deleteRecursively()

        robot.interact { editor.closeUnder(folder, discarded) }

        assertEquals(listOf(inner), discarded)
        assertEquals(listOf(other, later), editor.openPaths)
        assertEquals(listOf("other.txt", "Terminal", "\u25cf later.txt"), tabTitles(robot))
        assertEquals("later.txt was deleted on disk; saving recreates it.", bannerText(robot))
        assertFalse(inner.exists(), "nothing saved")

        robot.interact { editor.closeUnder(other, emptyList()) }
        assertEquals(listOf("Terminal", "\u25cf later.txt"), tabTitles(robot))
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

    /** Opens [files], edits each and records what [EditorArea.confirmSave] is asked, answering [answer]. */
    private fun openEdited(
        robot: FxRobot,
        vararg files: Path,
        answer: () -> SaveChoice,
    ): List<List<String>> {
        val asked = mutableListOf<List<String>>()
        editor.projectFolder = { dir }
        editor.confirmSave = { names ->
            asked += names
            answer()
        }
        for (file in files) {
            open(robot, file)
            robot.interact { shownArea(robot).insertText(0, "edited ") }
        }
        return asked
    }

    @Test
    fun `closing a tab with unsaved changes asks to save, Cancel keeps it and Don't Save discards them`(
        robot: FxRobot,
    ) {
        val a = textFile("a.txt", "a")
        var answer = SaveChoice.CANCEL
        val asked = openEdited(robot, a) { answer }
        val close = tabLabel(robot, "\u25cf a.txt").parent.lookup(".close-button") as Button

        robot.interact { close.fire() }
        assertEquals(listOf(listOf("a.txt")), asked)
        assertEquals(listOf("\u25cf a.txt"), tabTitles(robot))

        answer = SaveChoice.DISCARD
        robot.interact { close.fire() }
        assertEquals(emptyList(), tabTitles(robot))
        assertEquals("a", a.readText())
    }

    @Test
    fun `Save saves the files before their tabs close, and close all asks once for all of them`(robot: FxRobot) {
        val a = textFile("a.txt", "a")
        val b = textFile("b.txt", "b")
        val asked = openEdited(robot, a, b) { SaveChoice.SAVE }
        open(robot, textFile("clean.txt"))

        robot.interact {
            editor.tabStrip
                .contextMenuOf(checkNotNull(editor.editorTabs.find(a)))
                .items
                .first { it.text == "Close All" }
                .fire()
        }

        assertEquals(listOf(listOf("a.txt", "b.txt")), asked)
        assertEquals(emptyList(), tabTitles(robot))
        assertEquals(listOf("edited a", "edited b"), listOf(a.readText(), b.readText()))
    }

    @Test
    fun `asking to save lists the files by their path in the project, or in full outside of it`(robot: FxRobot) {
        val app =
            dir
                .resolve("app")
                .createDirectories()
                .resolve("build.gradle.kts")
                .apply { writeText("app") }
        val lib =
            dir
                .resolve("lib")
                .createDirectories()
                .resolve("build.gradle.kts")
                .apply { writeText("lib") }
        val outside = settingsDir.resolve("notes.txt").apply { writeText("notes") }
        val asked = openEdited(robot, app, lib, outside) { SaveChoice.CANCEL }

        robot.interact {
            editor.tabStrip
                .contextMenuOf(editor.tabs.first())
                .items
                .first { it.text == "Close All" }
                .fire()
        }

        assertEquals(listOf(listOf("app/build.gradle.kts", "lib/build.gradle.kts", outside.toString())), asked)
    }

    @Test
    fun `confirmCloseAll asks once for all edited files and is false when cancelled`(robot: FxRobot) {
        val a = textFile("a.txt", "a")
        val b = textFile("b.txt", "b")
        var answer = SaveChoice.CANCEL
        val asked = openEdited(robot, a, b) { answer }

        robot.interact { assertFalse(editor.confirmCloseAll()) }
        answer = SaveChoice.DISCARD
        robot.interact { assertTrue(editor.confirmCloseAll()) }

        assertEquals(List(2) { listOf("a.txt", "b.txt") }, asked)
        assertEquals(listOf("a", "b"), listOf(a.readText(), b.readText()))
    }

    @Test
    fun `a tab whose file cannot be saved stays open`(robot: FxRobot) {
        val fallback = DefaultTextFileViewer()
        val area = EditorArea(FileViewers(listOf(fallback, UnsavableViewer()), fallback), watcher, fontSettings)
        val errors = mutableListOf<String>()
        area.showError = { errors += it }
        area.confirmSave = { SaveChoice.SAVE }
        robot.interact {
            stage.scene = Scene(area, 800.0, 400.0)
            area.open(textFile("notes.unsavable"))
        }
        val tab = area.tabs.single()

        robot.interact {
            area.tabStrip
                .contextMenuOf(tab)
                .items
                .first { it.text == "Close" }
                .fire()
        }

        assertEquals(listOf(tab), area.tabs)
        assertEquals(1, errors.size)
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
