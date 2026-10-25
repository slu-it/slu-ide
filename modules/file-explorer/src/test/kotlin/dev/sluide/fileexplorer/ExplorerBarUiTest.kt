package dev.sluide.fileexplorer

import dev.sluide.filewatcher.FileWatcher
import javafx.application.Platform
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.control.Control
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The button bar above the explorer, wired to a real view. Needs a display, see the `uiTest` task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ExplorerBarUiTest : FileExplorerViewUiFixture() {
    private val opened = SimpleObjectProperty<Path?>()
    private var selectRequests = 0
    private lateinit var bar: ExplorerBar

    @Start
    fun start(stage: Stage) =
        startView(stage, wrap = {
            bar = ExplorerBar(it, opened, { selectRequests++ }, "Alt+F1")
            VBox(bar, it)
        })

    @Test
    fun `the bar has the four buttons with tooltips naming the action and key`(robot: FxRobot) {
        robot.interact {
            assertEquals(
                listOf("Select Opened File (Alt+F1)", "Collapse All", "Refresh (F5)", "Compact Middle Folders"),
                bar.children.map { (it as Control).tooltip.text },
            )
            assertTrue(bar.children.none { it.isFocusTraversable })
        }
    }

    @Test
    fun `Select Opened File is enabled for a file under a root only and calls the window`(robot: FxRobot) {
        val file = project.resolve("a.txt").createFile()
        robot.interact { view.open(project) }
        robot.interact { assertTrue(bar.selectOpenedButton.isDisable) }

        robot.interact { opened.value = file }
        robot.interact { assertFalse(bar.selectOpenedButton.isDisable) }
        robot.interact { bar.selectOpenedButton.fire() }
        assertEquals(1, selectRequests)

        robot.interact { opened.value = settingsDir.resolve("elsewhere.txt") }
        robot.interact { assertTrue(bar.selectOpenedButton.isDisable) }
    }

    @Test
    fun `Select Opened File becomes enabled when the project opens after the file is known`(robot: FxRobot) {
        val file = project.resolve("a.txt").createFile()
        robot.interact { opened.value = file }
        robot.interact { assertTrue(bar.selectOpenedButton.isDisable, "no project yet") }

        robot.interact { view.open(project) }

        robot.interact { assertFalse(bar.selectOpenedButton.isDisable) }
    }

    @Test
    fun `Collapse All collapses the folders and keeps the project open`(robot: FxRobot) {
        project.resolve("sub/deeper").createDirectories()
        project.resolve("sub/deeper/f.txt").createFile()
        robot.interact { view.open(project) }
        robot.interact { view.reveal(project.resolve("sub/deeper/f.txt")) }
        assertEquals(listOf(project.name, "sub", "deeper", "f.txt"), renderedNames(robot))

        robot.interact { bar.collapseAllButton.fire() }

        assertEquals(listOf(project.name, "sub"), renderedNames(robot))
        robot.interact { assertEquals(project.resolve("sub"), view.selectionModel.selectedItem?.value) }
    }

    @Test
    fun `Refresh re-reads the loaded folders`(robot: FxRobot) {
        // Without working watches, only a refresh can show the new file.
        val unwatched = FileWatcher(Platform::runLater, 50.milliseconds).apply { close() }
        lateinit var manual: FileExplorerView
        robot.interact {
            manual = FileExplorerView(unwatched, fontSettings, ExplorerClipboard(), explorerSettings)
            val manualBar = ExplorerBar(manual, opened, {}, "Alt+F1")
            view.scene.root = VBox(manualBar, manual)
            manual.open(project)
            bar = manualBar
        }
        project.resolve("late.txt").createFile()

        robot.interact { bar.refreshButton.fire() }

        assertEquals(listOf(project.name, "late.txt"), renderedNames(robot))
    }

    @Test
    fun `the compact toggle follows the setting and switches the tree, keeping the selection and expansion`(
        robot: FxRobot,
    ) {
        project.resolve("src/main/kotlin").createDirectories()
        val app = project.resolve("src/main/kotlin/App.kt").createFile()
        project.resolve("README.md").createFile()
        robot.interact { view.open(project) }
        robot.interact { view.reveal(app) }
        assertFalse(bar.compactToggle.isSelected, "off in the fixture")
        assertEquals(listOf(project.name, "src", "main", "kotlin", "App.kt", "README.md"), renderedNames(robot))

        robot.interact { bar.compactToggle.fire() }

        assertTrue(explorerSettings.compactFolders.get())
        assertEquals(listOf(project.name, "src/main/kotlin", "App.kt", "README.md"), renderedNames(robot))
        robot.interact { assertEquals(app, view.selectionModel.selectedItem?.value) }

        robot.interact { view.reveal(project.resolve("src/main")) }
        robot.interact { explorerSettings.compactFolders.set(false) }

        assertFalse(bar.compactToggle.isSelected)
        assertEquals(listOf(project.name, "src", "main", "kotlin", "App.kt", "README.md"), renderedNames(robot))
        robot.interact { assertEquals(project.resolve("src/main/kotlin"), view.selectionModel.selectedItem?.value) }
    }
}
