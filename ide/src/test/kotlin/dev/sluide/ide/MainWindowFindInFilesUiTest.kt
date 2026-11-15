package dev.sluide.ide

import javafx.event.Event
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.control.TreeCell
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Popup
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Find in Files in the real main window (ADR 0177): the Navigate item opens the popup over the explorer's listing, the
 * focused explorer folder scopes it, a match opens its file at the line also in a window that never had a code editor,
 * and an unsaved edit is searched. The search and the popup are the find-in-files module's tests; this is the wiring.
 * Every test needs a scene: a real window with a popup, the explorer's focus and the editor tabs.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowFindInFilesUiTest : MainWindowUiFixture() {
    @Start
    fun start(stage: Stage) {
        project.resolve("src").createDirectories()
        project.resolve("src/a.kt").writeText("package a\n\nval needle = 1\n")
        project.resolve("docs").createDirectories()
        project.resolve("docs/b.md").writeText("a needle in the docs\n")
        // A folder that ignores everything is generated: Hide Generated Files leaves it out of the tree.
        project.resolve("build").createDirectories()
        project.resolve("build/.gitignore").writeText("*\n")
        project.resolve("build/gen.kt").writeText("val needle = 2\n")
        projectService.open(project)
        startView(stage)
    }

    private fun findPopup(): Popup? =
        Window.getWindows().filterIsInstance<Popup>().firstOrNull {
            it.isShowing &&
                it.scene.lookup(".find-in-files-list") != null
        }

    private fun queryField(): TextField = checkNotNull(findPopup()).scene.lookup(".find-in-files-field") as TextField

    /** The file names of the result list's header rows. */
    private fun resultFiles(): Set<String> =
        findPopup()
            ?.scene
            ?.lookup(".find-in-files-list")
            ?.lookupAll(".find-in-files-name")
            ?.filterIsInstance<Label>()
            ?.filter { it.isVisible && it.scene != null }
            ?.map { it.text }
            ?.toSet()
            .orEmpty()

    private fun openFind(robot: FxRobot) {
        robot.interact { menu(robot, "Navigate").items.single { it.text == "Find in Files…" }.fire() }
        eventually(robot) { assertTrue(findPopup() != null, "the popup shows") }
    }

    private fun type(
        robot: FxRobot,
        query: String,
    ) = robot.interact { queryField().text = query }

    private fun pressInPopup(
        robot: FxRobot,
        code: KeyCode,
    ) = robot.interact {
        Event.fireEvent(queryField(), KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))
    }

    @Test
    fun `the Navigate item opens the popup over the files the tree shows, not the generated ones`(robot: FxRobot) {
        openFind(robot)

        type(robot, "needle")

        eventually(robot) { assertEquals(setOf("a.kt", "b.md"), resultFiles()) }
    }

    @Test
    fun `with a folder selected in the focused explorer the item searches only that folder`(robot: FxRobot) {
        robot.interact { mainWindow.explorer.select(project.resolve("docs")) }
        eventually(robot) { assertEquals(project.resolve("docs"), fileExplorer.focusedFolder()) }

        openFind(robot)
        type(robot, "needle")

        eventually(robot) { assertEquals(setOf("b.md"), resultFiles()) }
    }

    @Test
    fun `Enter on a match opens its file at the line in a window that never had a code editor`(robot: FxRobot) {
        robot.interact { assertTrue(editorArea.openPaths.isEmpty()) }
        openFind(robot)
        type(robot, "needle")
        eventually(robot) {
            assertEquals(setOf("a.kt", "b.md"), resultFiles())
            val list = checkNotNull(findPopup()).scene.lookup(".find-in-files-list") as ListView<*>
            assertTrue(list.selectionModel.selectedIndex >= 0, "the first match is selected")
        }

        // The first match is in docs/b.md: Down moves to the one in src/a.kt, skipping its header row.
        pressInPopup(robot, KeyCode.DOWN)
        pressInPopup(robot, KeyCode.ENTER)

        eventually(robot) {
            assertEquals(project.resolve("src/a.kt"), editorArea.selectedPath)
            val area = shownArea(robot)
            assertEquals(2 to 4, area.currentParagraph to area.caretColumn, "line and column of the match, 0-based")
        }
    }

    @Test
    fun `a match in an unsaved edit of an open editor is found`(robot: FxRobot) {
        robot.interact {
            editorArea.open(project.resolve("src/a.kt"))
            shownArea(robot).appendText("\nunsaved-token")
        }

        openFind(robot)
        type(robot, "unsaved-token")

        eventually(robot) { assertEquals(setOf("a.kt"), resultFiles()) }
    }

    @Test
    fun `a single-line selection in the focused editor becomes the query, through the editor extension`(
        robot: FxRobot,
    ) {
        robot.interact {
            editorArea.open(project.resolve("src/a.kt"))
            val area = shownArea(robot)
            area.selectRange(2, 4, 2, 10)
            area.requestFocus()
        }

        openFind(robot)

        eventually(robot) { assertEquals("needle", queryField().text) }
    }

    @Test
    fun `Find in Folder in the explorer's folder menu searches that folder`(robot: FxRobot) {
        val cell =
            robot
                .lookup(".tree-cell")
                .queryAllAs(TreeCell::class.java)
                .single { (it.item as? Path)?.name == "docs" }
        robot.interact {
            Event.fireEvent(
                cell,
                ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1.0, 1.0, 1.0, 1.0, false, null),
            )
        }
        WaitForAsyncUtils.waitForFxEvents()
        val item = fileExplorer.contextMenu.items.single { it.text == "Find in Folder…" }
        robot.interact {
            fileExplorer.contextMenu.hide()
            item.fire()
        }
        eventually(robot) { assertTrue(findPopup() != null, "the popup shows") }

        type(robot, "needle")

        eventually(robot) { assertEquals(setOf("b.md"), resultFiles()) }
    }
}
