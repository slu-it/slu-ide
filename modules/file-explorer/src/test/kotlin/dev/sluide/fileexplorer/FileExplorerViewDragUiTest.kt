package dev.sluide.fileexplorer

import dev.sluide.fileexplorer.actions.MoveQuestion
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import javafx.application.Platform
import javafx.event.Event
import javafx.event.EventType
import javafx.geometry.Orientation
import javafx.geometry.Point2D
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.ScrollBar
import javafx.scene.control.TextField
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.PickResult
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Popup
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Dragging entries with the mouse, as synthetic events: a press on a row, drags (delivered to the pressed row, as
 * JavaFX does) and a release. The move flow behind a drop is tested in `ExplorerMovesTest`. Needs a display,
 * see `uiTest`.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewDragUiTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var settingsDir: Path

    private lateinit var view: FileExplorerView
    private lateinit var other: TextField
    private val watcher = FileWatcher(Platform::runLater, 50.milliseconds)
    private val questions = mutableListOf<MoveQuestion>()
    private val opened = mutableListOf<Path>()

    @Start
    fun start(stage: Stage) {
        val fontSettings =
            FontSettings(SettingsStore(settingsDir.resolve("settings.properties")), FontSettings.systemDefaults())
        view = FileExplorerView(watcher, fontSettings)
        view.confirmMove = {
            questions += it
            true
        }
        view.onOpenFile = { opened.add(it) }
        other = TextField()
        stage.scene = Scene(VBox(other, view), 400.0, 600.0)
        stage.show()
    }

    @AfterEach
    fun closeWatcher() = watcher.close()

    private fun open(
        robot: FxRobot,
        vararg expand: String,
    ) {
        robot.interact {
            view.open(project)
            expand.forEach { item(it).isExpanded = true }
        }
    }

    private fun item(name: String): TreeItem<Path> =
        name.split('/').fold(view.projectNode) { folder, segment ->
            folder.children.first { it.value.fileName.toString() == segment }
        }

    /** The shown row of [name], laid out first: right after an expansion the rows may not be placed yet. */
    private fun cellNamed(
        robot: FxRobot,
        name: String,
    ): TreeCell<*> {
        robot.interact {
            view.applyCss()
            view.layout()
        }
        return robot
            .lookup(".tree-cell")
            .queryAllAs(TreeCell::class.java)
            .first { it.isVisible && !it.isEmpty && it.text == name }
    }

    /** The middle of the row of [name], in the scene. */
    private fun at(
        robot: FxRobot,
        name: String,
    ): Point2D = cellNamed(robot, name).let { it.localToScene(it.width / 2, it.height / 2) }

    private fun selectedPaths(): List<Path> =
        view.selectionModel.selectedItems
            .filterNotNull()
            .sortedBy { view.getRow(it) }
            .map { it.value }

    private fun select(
        robot: FxRobot,
        vararg names: String,
    ) = robot.interact {
        view.selectionModel.clearSelection()
        names.forEach { view.selectionModel.select(item(it)) }
    }

    /**
     * Fires a mouse event of [type] for [button] at [node] for the pointer at [point] in the scene, the primary button
     * [down] and the secondary one [secondaryDown] afterwards.
     */
    private fun mouse(
        robot: FxRobot,
        node: Node,
        type: EventType<MouseEvent>,
        point: Point2D,
        down: Boolean,
        clickCount: Int = 1,
        button: MouseButton = MouseButton.PRIMARY,
        secondaryDown: Boolean = false,
    ) {
        robot.interact {
            val screen = view.scene.root.localToScreen(point)
            Event.fireEvent(
                node,
                MouseEvent(
                    node,
                    node,
                    type,
                    point.x,
                    point.y,
                    screen.x,
                    screen.y,
                    button,
                    clickCount,
                    false,
                    false,
                    false,
                    false,
                    down,
                    false,
                    secondaryDown,
                    false,
                    button == MouseButton.SECONDARY && type == MouseEvent.MOUSE_PRESSED,
                    false,
                    PickResult(node, point.x, point.y),
                ),
            )
        }
    }

    /** A drag started on the row of [name]: pressed, then moved past the threshold. Returns the pressed row. */
    private fun startDrag(
        robot: FxRobot,
        name: String,
    ): TreeCell<*> {
        val cell = cellNamed(robot, name)
        val start = cell.localToScene(cell.width / 2, cell.height / 2)
        mouse(robot, cell, MouseEvent.MOUSE_PRESSED, start, true)
        mouse(robot, cell, MouseEvent.MOUSE_DRAGGED, start.add(0.0, 10.0), true)
        return cell
    }

    private fun dragTo(
        robot: FxRobot,
        pressed: Node,
        point: Point2D,
    ) = mouse(robot, pressed, MouseEvent.MOUSE_DRAGGED, point, true)

    private fun release(
        robot: FxRobot,
        pressed: Node,
        point: Point2D,
    ) = mouse(robot, pressed, MouseEvent.MOUSE_RELEASED, point, false)

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10.seconds.inWholeNanoseconds
        while (!condition() && System.nanoTime() < deadline) {
            WaitForAsyncUtils.waitForFxEvents()
            Thread.sleep(50)
        }
    }

    /** The image beside the pointer while dragging; `null` if none shows. */
    private fun dragImage(robot: FxRobot): HBox? {
        var image: HBox? = null
        robot.interact {
            image =
                Window
                    .getWindows()
                    .filterIsInstance<Popup>()
                    .filter { it.isShowing }
                    .flatMap { it.content }
                    .filterIsInstance<HBox>()
                    .singleOrNull { "explorer-drag-image" in it.styleClass }
        }
        return image
    }

    private fun HBox.texts() = children.filterIsInstance<Label>().map { it.text }

    private fun TreeCell<*>.isDropTarget() = pseudoClassStates.any { it.pseudoClassName == "drop-target" }

    @Test
    fun `dragging a file onto a folder marks it, expands it when held and moves the file after the question`(
        robot: FxRobot,
    ) {
        project.resolve("a.txt").createFile()
        project.resolve("target").createDirectories()
        project.resolve("target/inside.txt").createFile()
        open(robot)
        val target = cellNamed(robot, "target")

        val pressed = startDrag(robot, "a.txt")
        dragTo(robot, pressed, at(robot, "target"))

        // Sized to icon and name, the name readable on the image's background (the row is selected, its text white).
        val image = checkNotNull(dragImage(robot))
        assertEquals(listOf("a.txt"), image.texts())
        assertTrue(image.width < view.width / 2, "as wide as its content, not the row: ${image.width}")
        val name = image.children.filterIsInstance<Label>().single()
        val background =
            image.background.fills
                .last()
                .fill as Color
        assertTrue((name.textFill as Color).brightness - background.brightness < -0.5, "dark text on a light ground")
        assertTrue(target.isDropTarget())
        assertSame(item("target"), view.dropTarget())
        waitFor { item("target").isExpanded }
        assertTrue(item("target").isExpanded, "held long enough, the folder expands")

        release(robot, pressed, at(robot, "target"))
        waitFor { project.resolve("target/a.txt").exists() }

        assertEquals("Move “a.txt” to “target”?", questions.single().header)
        assertTrue(project.resolve("target/a.txt").exists())
        assertFalse(target.isDropTarget())
        assertNull(dragImage(robot))
    }

    @Test
    fun `a dropped expanded folder is selected in its new folder and stays expanded`(robot: FxRobot) {
        project.resolve("src/main").createDirectories()
        project.resolve("src/main/inner.txt").createFile()
        project.resolve("target").createDirectories()
        open(robot, "src")

        val pressed = startDrag(robot, "src")
        dragTo(robot, pressed, at(robot, "target"))
        release(robot, pressed, at(robot, "target"))
        waitFor { project.resolve("target/src/main/inner.txt").exists() }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact {
            assertTrue(item("target").isExpanded)
            assertTrue(item("target/src").isExpanded, "a moved folder stays expanded")
            assertEquals(listOf(project.resolve("target/src")), selectedPaths())
        }
    }

    @Test
    fun `a file is no target, and the release neither moves nor opens anything`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        project.resolve("b.txt").createFile()
        open(robot)

        val pressed = startDrag(robot, "a.txt")
        dragTo(robot, pressed, at(robot, "b.txt"))
        assertNull(view.dropTarget())
        assertFalse(cellNamed(robot, "b.txt").isDropTarget())
        release(robot, pressed, at(robot, "a.txt"))
        // The click that may follow a release on the pressed row, also as a double-click.
        mouse(robot, pressed, MouseEvent.MOUSE_CLICKED, at(robot, "a.txt"), false, clickCount = 2)
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(emptyList(), questions)
        assertEquals(emptyList(), opened)
        assertEquals(listOf(project.resolve("a.txt")), selectedPaths())
    }

    @Test
    fun `the scroll bar is no target`(robot: FxRobot) {
        project.resolve("src").createDirectories()
        project.resolve("src/a.txt").createFile()
        (1..60).forEach { project.resolve("f%02d.txt".format(it)).createFile() }
        open(robot, "src")
        val bar =
            robot
                .lookup(".scroll-bar")
                .queryAllAs(ScrollBar::class.java)
                .first { it.orientation == Orientation.VERTICAL && it.isVisible }
        val onBar = bar.localToScene(bar.width / 2, bar.height / 2)

        val pressed = startDrag(robot, "a.txt")
        dragTo(robot, pressed, onBar)
        assertNull(view.dropTarget())
        release(robot, pressed, onBar)
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(emptyList(), questions)
        assertTrue(project.resolve("src/a.txt").exists())
    }

    @Test
    fun `the empty area below the rows stands for the project folder`(robot: FxRobot) {
        project.resolve("src").createDirectories()
        project.resolve("src/a.txt").createFile()
        open(robot, "src")
        val empty = view.localToScene(view.width / 2, view.height - 50)

        val pressed = startDrag(robot, "a.txt")
        dragTo(robot, pressed, empty)
        assertSame(view.projectNode, view.dropTarget())
        release(robot, pressed, empty)
        waitFor { project.resolve("a.txt").exists() }

        assertEquals("Move “a.txt” to “${project.fileName}”?", questions.single().header)
        assertTrue(project.resolve("a.txt").exists())
    }

    @Test
    fun `a drag ending without a move selects the dragged entries again, nested ones too`(robot: FxRobot) {
        project.resolve("lib").createDirectories()
        project.resolve("lib/inside.txt").createFile()
        project.resolve("src").createDirectories()
        project.resolve("src/b.txt").createFile()
        project.resolve("a.txt").createFile()
        open(robot, "src")
        select(robot, "src", "src/b.txt")

        // The folder above the dragged entries expands, which can lose the selection.
        val pressed = startDrag(robot, "src")
        dragTo(robot, pressed, at(robot, "lib"))
        waitFor { item("lib").isExpanded }
        dragTo(robot, pressed, at(robot, "a.txt"))
        release(robot, pressed, at(robot, "a.txt"))

        assertEquals(emptyList(), questions)
        assertEquals(listOf(project.resolve("src"), project.resolve("src/b.txt")), selectedPaths())
    }

    @Test
    fun `Escape cancels the drag and the rest of the press does nothing`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        project.resolve("target").createDirectories()
        open(robot)

        val pressed = startDrag(robot, "a.txt")
        dragTo(robot, pressed, at(robot, "target"))
        robot.interact {
            Event.fireEvent(view, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false))
        }
        assertNull(view.dropTarget())
        dragTo(robot, pressed, at(robot, "target"))
        assertNull(view.dropTarget())
        release(robot, pressed, at(robot, "target"))
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(emptyList(), questions)
        assertTrue(project.resolve("a.txt").exists())
        assertEquals(listOf(project.resolve("a.txt")), selectedPaths())
    }

    @Test
    fun `pressing a selected row of several drags all of them`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        project.resolve("b.txt").createFile()
        project.resolve("target").createDirectories()
        open(robot)
        select(robot, "a.txt", "b.txt")

        val pressed = startDrag(robot, "a.txt")
        assertEquals(listOf(project.resolve("a.txt"), project.resolve("b.txt")), selectedPaths())
        assertEquals(listOf("a.txt", "+1"), dragImage(robot)?.texts())
        dragTo(robot, pressed, at(robot, "target"))
        release(robot, pressed, at(robot, "target"))
        waitFor { project.resolve("target/b.txt").exists() }

        assertEquals("Move 2 entries to “target”?", questions.single().header)
        assertTrue(project.resolve("target/a.txt").exists())
        assertTrue(project.resolve("target/b.txt").exists())
    }

    @Test
    fun `a press and release within the threshold is a click that selects only that row`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        project.resolve("b.txt").createFile()
        project.resolve("target").createDirectories()
        open(robot)
        select(robot, "a.txt", "b.txt")
        val cell = cellNamed(robot, "a.txt")
        val start = at(robot, "a.txt")

        mouse(robot, cell, MouseEvent.MOUSE_PRESSED, start, true)
        mouse(robot, cell, MouseEvent.MOUSE_DRAGGED, start.add(2.0, 1.0), true)
        assertNull(view.dropTarget())
        mouse(robot, cell, MouseEvent.MOUSE_RELEASED, start.add(2.0, 1.0), false)
        mouse(robot, cell, MouseEvent.MOUSE_CLICKED, start.add(2.0, 1.0), false)

        assertEquals(emptyList(), questions)
        assertEquals(listOf(project.resolve("a.txt")), selectedPaths())
    }

    @Test
    fun `a secondary press cancels the drag without a menu, and a later click on a folder moves nothing`(
        robot: FxRobot,
    ) {
        project.resolve("a.txt").createFile()
        project.resolve("target").createDirectories()
        open(robot)
        val target = at(robot, "target")
        val folderCell = cellNamed(robot, "target")

        val pressed = startDrag(robot, "a.txt")
        dragTo(robot, pressed, target)
        // As the platform delivers it: the press at the row under the pointer, then the menu request it triggers.
        mouse(
            robot,
            folderCell,
            MouseEvent.MOUSE_PRESSED,
            target,
            true,
            button = MouseButton.SECONDARY,
            secondaryDown = true,
        )
        robot.interact {
            val screen = view.scene.root.localToScreen(target)
            Event.fireEvent(
                folderCell,
                ContextMenuEvent(
                    ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    target.x,
                    target.y,
                    screen.x,
                    screen.y,
                    false,
                    PickResult(folderCell, target.x, target.y),
                ),
            )
        }

        assertNull(view.dropTarget())
        assertNull(dragImage(robot))
        assertFalse(view.contextMenu.isShowing)
        assertEquals(listOf(project.resolve("a.txt")), selectedPaths())
        mouse(robot, pressed, MouseEvent.MOUSE_RELEASED, target, true, button = MouseButton.SECONDARY)
        release(robot, pressed, target)
        assertEquals(listOf(project.resolve("a.txt")), selectedPaths())

        // A plain click on the folder afterwards is only a click.
        mouse(robot, folderCell, MouseEvent.MOUSE_PRESSED, target, true)
        mouse(robot, folderCell, MouseEvent.MOUSE_RELEASED, target, false)
        mouse(robot, folderCell, MouseEvent.MOUSE_CLICKED, target, false)
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(emptyList(), questions)
        assertTrue(project.resolve("a.txt").exists())
        assertEquals(listOf(project.resolve("target")), selectedPaths())
    }

    @Test
    fun `losing the focus cancels the drag`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        project.resolve("target").createDirectories()
        open(robot)
        val pressed = cellNamed(robot, "a.txt")
        val start = at(robot, "a.txt")
        mouse(robot, pressed, MouseEvent.MOUSE_PRESSED, start, true)
        // The tree lacks the focus when the drag starts.
        robot.interact { other.requestFocus() }

        mouse(robot, pressed, MouseEvent.MOUSE_DRAGGED, start.add(0.0, 10.0), true)
        dragTo(robot, pressed, at(robot, "target"))
        assertTrue(view.isFocused, "a drag takes the focus")
        assertSame(item("target"), view.dropTarget())
        robot.interact { other.requestFocus() }

        assertNull(view.dropTarget())
        assertNull(dragImage(robot))
        release(robot, pressed, at(robot, "target"))
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), questions)
        assertEquals(listOf(project.resolve("a.txt")), selectedPaths())
    }

    @Test
    fun `opening another folder cancels the drag`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        project.resolve("target").createDirectories()
        open(robot)

        val pressed = startDrag(robot, "a.txt")
        dragTo(robot, pressed, at(robot, "target"))
        open(robot)

        assertNull(view.dropTarget())
        assertNull(dragImage(robot))
        dragTo(robot, pressed, at(robot, "target"))
        assertNull(view.dropTarget())
        release(robot, pressed, at(robot, "target"))
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), questions)
        assertTrue(project.resolve("a.txt").exists())
    }

    @Test
    fun `Escape reaches the drag image while it shows and cancels the drag`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        project.resolve("target").createDirectories()
        open(robot)

        val pressed = startDrag(robot, "a.txt")
        dragTo(robot, pressed, at(robot, "target"))
        val popupScene = checkNotNull(dragImage(robot)).scene
        // As the window's key events reach a showing popup.
        robot.interact {
            Event.fireEvent(
                popupScene,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false),
            )
        }

        assertNull(view.dropTarget())
        assertNull(dragImage(robot))
        release(robot, pressed, at(robot, "target"))
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), questions)
        assertEquals(listOf(project.resolve("a.txt")), selectedPaths())
    }
}
