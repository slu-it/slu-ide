package dev.sluide.projects

import javafx.event.Event
import javafx.geometry.Orientation
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.ScrollBar
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import javafx.stage.WindowEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Shows the real picker. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ProjectPickerUiTest {
    @TempDir
    lateinit var dir: Path

    private val opened = mutableListOf<Path>()
    private var cancelled = 0
    private lateinit var store: RecentProjects
    private lateinit var picker: ProjectPicker
    private lateinit var stage: Stage

    @Start
    fun start(owner: Stage) {
        owner.scene = Scene(StackPane(), 100.0, 100.0)
        owner.show()
    }

    @AfterEach
    fun closePicker(robot: FxRobot) {
        if (::stage.isInitialized) robot.interact { stage.hide() }
    }

    private fun folder(name: String) = dir.resolve("home").resolve(name).createDirectories()

    private fun show(
        robot: FxRobot,
        chooser: (Stage) -> Path? = { null },
    ): Stage {
        store = RecentProjects(dir.resolve("projects.properties"))
        return showWith(robot, chooser)
    }

    private fun showWith(
        robot: FxRobot,
        chooser: (Stage) -> Path? = { null },
    ): Stage {
        picker = ProjectPicker(store)
        picker.home = dir.resolve("home")
        picker.chooseFolder = chooser
        robot.interact { stage = picker.show({ opened.add(it) }, { cancelled++ }) }
        WaitForAsyncUtils.waitForFxEvents()
        return stage
    }

    @Suppress("UNCHECKED_CAST")
    private fun cells(): List<ListCell<*>> {
        val list = stage.scene.root as ListView<*>
        return list
            .lookupAll(".list-cell")
            .filterIsInstance<ListCell<*>>()
            .filter { !it.isEmpty }
            .sortedBy { it.index }
    }

    private fun Node.labels(style: String) = lookupAll(".$style").filterIsInstance<Label>().map { it.text }

    private fun click(
        robot: FxRobot,
        node: Node,
    ) {
        robot.interact {
            fireClick(node)
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun fireClick(node: Node) =
        Event.fireEvent(
            node,
            MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                1.0,
                1.0,
                1.0,
                1.0,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                false,
                null,
            ),
        )

    private fun key(
        robot: FxRobot,
        code: KeyCode,
    ) {
        robot.interact {
            Event.fireEvent(
                stage.scene.focusOwner,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false),
            )
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    @Test
    fun `an empty store offers only to open a new project`(robot: FxRobot) {
        show(robot)

        assertEquals(1, cells().size)
        assertEquals(listOf("Open new project…"), cells().single().graphic.labels("project-name"))
    }

    @Test
    fun `the window is titled Thoreau unless given a title`(robot: FxRobot) {
        assertEquals("Thoreau", show(robot).title)

        robot.interact {
            stage.hide()
            stage = picker.show({}, {}, title = "Thoreau v1.2.3")
        }

        assertEquals("Thoreau v1.2.3", stage.title)
    }

    @Test
    fun `a long path shows no horizontal scroll bar`(robot: FxRobot) {
        store = RecentProjects(dir.resolve("projects.properties"))
        store.opened(folder("a-rather-long-project-name-".repeat(6)))
        showWith(robot)

        val bars = list().lookupAll(".scroll-bar").filterIsInstance<ScrollBar>()
        assertTrue(bars.none { it.orientation == Orientation.HORIZONTAL && it.isVisible })
    }

    @Test
    fun `the empty rows below the entries are not striped`(robot: FxRobot) {
        show(robot)

        val empty =
            list()
                .lookupAll(".list-cell")
                .filterIsInstance<ListCell<*>>()
                .filter { it.isEmpty && it.isVisible }
        assertTrue(empty.size > 2, "${empty.size} empty rows")
        assertEquals(
            1,
            empty
                .map {
                    it.background
                        ?.fills
                        ?.firstOrNull()
                        ?.fill
                }.distinct()
                .size,
        )
    }

    @Test
    fun `projects are listed newest first with name and path`(robot: FxRobot) {
        store = RecentProjects(dir.resolve("projects.properties"))
        store.opened(folder("old"), Instant.ofEpochSecond(100))
        store.opened(folder("new"), Instant.ofEpochSecond(300))
        store.opened(folder("mid"), Instant.ofEpochSecond(200))
        showWith(robot)

        val rows = cells().drop(1)
        assertEquals(listOf("new", "mid", "old"), rows.map { it.graphic.labels("project-name").single() })
        assertEquals(listOf("~/new", "~/mid", "~/old"), rows.map { it.graphic.labels("project-path").single() })
        val name = rows.first().graphic.lookup(".project-name") as Label
        assertTrue(name.font.style.contains("Bold", ignoreCase = true) || name.font.name.contains("Bold"))
    }

    @Test
    fun `clicking a project opens it and closes the picker`(robot: FxRobot) {
        store = RecentProjects(dir.resolve("projects.properties"))
        val a = folder("a")
        store.opened(a)
        showWith(robot)

        click(robot, cells()[1])

        assertEquals(listOf(a), opened)
        assertFalse(stage.isShowing)
        assertEquals(0, cancelled)
    }

    @Test
    fun `the delete button removes the entry only`(robot: FxRobot) {
        store = RecentProjects(dir.resolve("projects.properties"))
        val a = folder("a")
        store.opened(a)
        showWith(robot)
        val button = cells()[1].graphic.lookup(".project-remove") as Button

        click(robot, button)
        assertEquals(2, cells().size)
        robot.interact { button.fire() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(1, cells().size)
        assertEquals(emptyList(), store.list())
        assertTrue(Files.isDirectory(a))
        assertTrue(opened.isEmpty())
        assertTrue(stage.isShowing)
    }

    @Test
    fun `a missing folder is marked and cannot be opened`(robot: FxRobot) {
        store = RecentProjects(dir.resolve("projects.properties"))
        val gone = dir.resolve("home/gone")
        store.opened(gone)
        showWith(robot)

        val cell = cells()[1]
        assertTrue(cell.styleClass.contains("missing"))
        assertEquals(listOf("~/gone — folder not found"), cell.graphic.labels("project-path"))
        click(robot, cell)
        assertTrue(opened.isEmpty())
        assertTrue(stage.isShowing)
    }

    @Test
    fun `open new project opens the chosen folder`(robot: FxRobot) {
        val chosen = folder("chosen")
        show(robot) { chosen }

        click(robot, cells()[0])

        assertEquals(listOf(chosen), opened)
        assertFalse(stage.isShowing)
        assertEquals(0, cancelled)
    }

    @Test
    fun `open new project stays when nothing is chosen`(robot: FxRobot) {
        show(robot) { null }

        click(robot, cells()[0])

        assertTrue(opened.isEmpty())
        assertEquals(0, cancelled)
        assertTrue(stage.isShowing)
    }

    @Test
    fun `escape and closing the window each cancel once`(robot: FxRobot) {
        val triggers =
            mapOf<String, () -> Unit>(
                "escape" to { key(robot, KeyCode.ESCAPE) },
                "closing the window" to {
                    robot.interact { stage.fireEvent(WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)) }
                    WaitForAsyncUtils.waitForFxEvents()
                },
            )
        for ((index, trigger) in triggers.entries.withIndex()) {
            show(robot)

            trigger.value()

            assertFalse(stage.isShowing, trigger.key)
            assertEquals(index + 1, cancelled, trigger.key)
            assertTrue(opened.isEmpty(), trigger.key)
        }
    }

    @Test
    fun `enter opens the selected project`(robot: FxRobot) {
        store = RecentProjects(dir.resolve("projects.properties"))
        val a = folder("a")
        store.opened(a)
        showWith(robot)
        select(robot, 1)

        key(robot, KeyCode.ENTER)

        assertEquals(listOf(a), opened)
        assertFalse(stage.isShowing)
    }

    @Test
    fun `enter on open new project asks for a folder`(robot: FxRobot) {
        var asked = 0
        show(robot) {
            asked++
            null
        }

        key(robot, KeyCode.ENTER)

        assertEquals(1, asked)
        assertTrue(stage.isShowing)
    }

    @Test
    fun `delete and backspace remove the selected project and select the next row`(robot: FxRobot) {
        store = RecentProjects(dir.resolve("projects.properties"))
        for ((i, name) in listOf("a", "b", "c").withIndex()) store.opened(folder(name), Instant.ofEpochSecond(i + 1L))
        showWith(robot)
        select(robot, 1)

        key(robot, KeyCode.DELETE)
        assertEquals(listOf("b", "a"), store.list().map { it.name })
        assertEquals(1, list().selectionModel.selectedIndex)

        key(robot, KeyCode.BACK_SPACE)
        assertEquals(listOf("a"), store.list().map { it.name })
        assertEquals(2, cells().size)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `delete on open new project does nothing`(robot: FxRobot) {
        store = RecentProjects(dir.resolve("projects.properties"))
        store.opened(folder("a"))
        showWith(robot)

        key(robot, KeyCode.DELETE)

        assertEquals(2, cells().size)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `a folder deleted after the picker opened is marked missing instead of opened`(robot: FxRobot) {
        store = RecentProjects(dir.resolve("projects.properties"))
        val a = folder("a")
        store.opened(a)
        showWith(robot)
        Files.delete(a)

        click(robot, cells()[1])

        assertTrue(opened.isEmpty())
        assertTrue(stage.isShowing)
        assertTrue(cells()[1].styleClass.contains("missing"))
    }

    @Test
    fun `a chosen folder is not opened when the picker was hidden meanwhile`(robot: FxRobot) {
        val chosen = folder("chosen")
        show(robot) {
            stage.hide()
            chosen
        }

        click(robot, cells()[0])

        assertTrue(opened.isEmpty())
        assertEquals(1, cancelled)
    }

    private fun list() = stage.scene.root as ListView<*>

    private fun select(
        robot: FxRobot,
        index: Int,
    ) {
        robot.interact { list().selectionModel.select(index) }
        WaitForAsyncUtils.waitForFxEvents()
    }
}
