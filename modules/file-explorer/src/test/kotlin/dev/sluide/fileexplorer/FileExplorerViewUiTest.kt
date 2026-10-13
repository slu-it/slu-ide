package dev.sluide.fileexplorer

import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import dev.sluide.settings.FontStyle
import dev.sluide.settings.SettingsStore
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.TreeCell
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
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
import kotlin.io.path.deleteExisting
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Renders the real view in a stage. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewUiTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var settingsDir: Path

    private lateinit var view: FileExplorerView
    private lateinit var fontSettings: FontSettings
    private val watcher = FileWatcher(Platform::runLater, 50.milliseconds)

    @Start
    fun start(stage: Stage) {
        fontSettings =
            FontSettings(SettingsStore(settingsDir.resolve("settings.properties")), FontSettings.systemDefaults())
        view = FileExplorerView(watcher, fontSettings)
        stage.scene = Scene(view, 400.0, 600.0)
        stage.show()
    }

    @AfterEach
    fun closeWatcher() = watcher.close()

    private fun renderedNames(robot: FxRobot): List<String> =
        robot
            .lookup(".tree-cell")
            .queryAllAs(TreeCell::class.java)
            .sortedBy { it.index } // lookups return cells in no particular order
            .mapNotNull { it.text }

    @Test
    fun `renders hidden files and folders, folders first`(robot: FxRobot) {
        project.resolve(".hidden-dir").createDirectories()
        project.resolve("sub").createDirectories()
        project.resolve(".hidden-file").createFile()
        project.resolve("visible.txt").createFile()

        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(
            listOf(project.name, ".hidden-dir", "sub", ".hidden-file", "visible.txt"),
            renderedNames(robot),
        )
    }

    @Test
    fun `expanding a folder renders its children`(robot: FxRobot) {
        project.resolve("sub/deeper").createDirectories()
        project.resolve("sub/inner.txt").createFile()
        robot.interact { view.open(project) }

        robot.interact {
            view.root.children
                .single()
                .isExpanded = true
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(project.name, "sub", "deeper", "inner.txt"), renderedNames(robot))
    }

    @Test
    fun `opening null clears the view`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        robot.interact { view.open(project) }

        robot.interact { view.open(null) }
        WaitForAsyncUtils.waitForFxEvents()

        assertNull(view.root)
        assertEquals(emptyList(), renderedNames(robot))
    }

    @Test
    fun `requests opening files but not folders`(robot: FxRobot) {
        val file = project.resolve("a.txt").createFile()
        val folder = project.resolve("sub").createDirectories()
        val requested = mutableListOf<Path>()
        view.onOpenFile = { requested.add(it) }

        robot.interact {
            assertTrue(view.requestOpen(file))
            assertFalse(view.requestOpen(folder))
        }

        assertEquals(listOf(file), requested)
    }

    @Test
    fun `Enter on a selected file requests opening it`(robot: FxRobot) {
        val file = project.resolve("a.txt").createFile()
        val requested = mutableListOf<Path>()
        view.onOpenFile = { requested.add(it) }
        robot.interact { view.open(project) }

        robot.interact {
            view.selectionModel.select(view.root.children.single())
            pressKey(KeyCode.ENTER)
            view.selectionModel.select(view.root)
            pressKey(KeyCode.ENTER)
        }

        assertEquals(listOf(file), requested)
    }

    private fun pressKey(code: KeyCode) =
        Event.fireEvent(view, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))

    @Test
    fun `double-click on a file requests opening it, on a folder it does not`(robot: FxRobot) {
        val folder = project.resolve("sub").createDirectories()
        val file = project.resolve("a.txt").createFile()
        val requested = mutableListOf<Path>()
        view.onOpenFile = { requested.add(it) }
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        val cells = robot.lookup(".tree-cell").queryAllAs(TreeCell::class.java).filter { it.item != null }
        robot.interact {
            doubleClick(cells.single { it.item == file })
            doubleClick(cells.single { it.item == folder })
        }

        assertEquals(listOf(file), requested)
    }

    private fun doubleClick(cell: TreeCell<*>) =
        Event.fireEvent(
            cell,
            MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                1.0,
                1.0,
                1.0,
                1.0,
                MouseButton.PRIMARY,
                2,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                null,
            ),
        )

    @Test
    fun `files created and deleted on disk appear and disappear`(robot: FxRobot) {
        project.resolve("existing.txt").createFile()
        robot.interact { view.open(project) }

        val created = project.resolve("created.txt").createFile()
        eventually(robot) { assertEquals(listOf(project.name, "created.txt", "existing.txt"), renderedNames(robot)) }

        created.deleteExisting()
        eventually(robot) { assertEquals(listOf(project.name, "existing.txt"), renderedNames(robot)) }
    }

    @Test
    fun `changes inside an expanded subfolder appear and keep the expansion`(robot: FxRobot) {
        project.resolve("sub").createDirectories()
        robot.interact { view.open(project) }
        robot.interact {
            view.root.children
                .single()
                .isExpanded = true
        }

        project.resolve("sub/inner.txt").createFile()
        project.resolve("top.txt").createFile()

        eventually(robot) {
            assertEquals(listOf(project.name, "sub", "inner.txt", "top.txt"), renderedNames(robot))
        }
    }

    @Test
    fun `Refresh in the context menu re-reads loaded folders`(robot: FxRobot) {
        // Without working watches, only a manual refresh can show the new file.
        val unwatched = FileWatcher(Platform::runLater, 50.milliseconds).apply { close() }
        lateinit var manual: FileExplorerView
        robot.interact {
            manual = FileExplorerView(unwatched, fontSettings)
            view.scene.root = manual
            manual.open(project)
        }
        project.resolve("late.txt").createFile()

        val refresh = manual.contextMenu.items.single()
        assertEquals("Refresh", refresh.text)
        robot.interact { refresh.fire() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(project.name, "late.txt"), renderedNames(robot))
    }

    @Test
    fun `F5 refreshes`(robot: FxRobot) {
        val unwatched = FileWatcher(Platform::runLater, 50.milliseconds).apply { close() }
        lateinit var manual: FileExplorerView
        robot.interact {
            manual = FileExplorerView(unwatched, fontSettings)
            view.scene.root = manual
            manual.open(project)
        }
        project.resolve("late.txt").createFile()

        robot.interact {
            Event.fireEvent(manual, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F5, false, false, false, false))
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(project.name, "late.txt"), renderedNames(robot))
    }

    @Test
    fun `a deleted and recreated subfolder keeps being watched`(robot: FxRobot) {
        val sub = project.resolve("sub").createDirectories()
        robot.interact { view.open(project) }
        robot.interact {
            view.root.children
                .single()
                .isExpanded = true
        }

        sub.toFile().deleteRecursively()
        sub.createDirectories()
        sub.resolve("first.txt").createFile()
        eventually(robot) { assertEquals(listOf(project.name, "sub", "first.txt"), renderedNames(robot)) }

        sub.resolve("second.txt").createFile()
        eventually(robot) {
            assertEquals(listOf(project.name, "sub", "first.txt", "second.txt"), renderedNames(robot))
        }
    }

    /** Repeats [assertion] on the FX thread until it passes; the JDK may poll for file changes on macOS. */
    private fun eventually(
        robot: FxRobot,
        assertion: () -> Unit,
    ) {
        val deadline = System.nanoTime() + 20.seconds.inWholeNanoseconds
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            try {
                var failure: AssertionError? = null
                robot.interact {
                    try {
                        assertion()
                    } catch (e: AssertionError) {
                        failure = e
                    }
                }
                failure?.let { throw it }
                return
            } catch (e: AssertionError) {
                if (System.nanoTime() > deadline) throw e
                Thread.sleep(50)
            }
        }
    }

    @Test
    fun `the tree uses the explorer font and follows its changes`(robot: FxRobot) {
        project.resolve("README.md").createFile()
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact { fontSettings.apply(mapOf(FontArea.EXPLORER to FontSpec("Serif", 19.0, FontStyle.BOLD))) }
        WaitForAsyncUtils.waitForFxEvents()

        val cell =
            robot
                .lookup(".tree-cell")
                .queryAllAs(TreeCell::class.java)
                .first { it.text == "README.md" }
        assertEquals(19.0, cell.font.size)
        assertEquals("Serif", cell.font.family)
        assertTrue("Bold" in cell.font.style, cell.font.style)
    }
}
