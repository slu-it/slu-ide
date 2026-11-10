package dev.sluide.ide.files

import dev.sluide.editorarea.EditorArea
import dev.sluide.projects.RecentProjects
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.stage.Stage
import javafx.stage.WindowEvent
import java.nio.file.Path

/**
 * The File menu of one window: the [scratchItems] (new scratch files), Open Folder… ([chooseFolder]), Open Project…
 * ([pickProject], Shortcut+Shift+O) and the Open Recent Project submenu (the [recentProjects] without the window's
 * own, opened by [openRecent]) | Save and Save All of the [editorArea] | Close Tab | Settings… ([showSettings]) |
 * Close Project (fires a close request on the [stage], like the window's close button). Used on the JavaFX thread.
 */
internal fun fileMenu(
    editorArea: EditorArea,
    scratchItems: List<MenuItem>,
    chooseFolder: () -> Unit,
    pickProject: () -> Unit,
    showSettings: () -> Unit,
    stage: Stage,
    recentProjects: RecentProjects,
    openRecent: (Path) -> Unit,
): Menu {
    val openFolder =
        MenuItem("Open Folder…").apply {
            accelerator = KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN)
            setOnAction { chooseFolder() }
        }
    // The project picker, to open a remembered project in another window (ADR 0034).
    val openProject =
        MenuItem("Open Project…").apply {
            accelerator = KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
            setOnAction { pickProject() }
        }
    // Ctrl+S in a terminal goes to the shell, which consumes the key before the accelerator sees it; Cmd+S on
    // macOS and Ctrl+Alt+S send nothing to the shell, so they save from a terminal too.
    val save =
        MenuItem("Save").apply {
            accelerator = KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN)
            setOnAction { editorArea.save() }
        }
    val saveAll =
        MenuItem("Save All").apply {
            accelerator = KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN)
            setOnAction { editorArea.saveAll() }
        }
    // Terminals close too, command tabs only once they have exited (`EditorTab.closableByShortcut`); on Linux
    // and Windows a focused terminal keeps Ctrl+W for the shell.
    val closeTab =
        MenuItem("Close Tab").apply {
            accelerator = KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN)
            disableProperty().bind(editorArea.canCloseSelected.map { !it })
            setOnAction { editorArea.closeSelected() }
        }
    val settings =
        MenuItem("Settings…").apply {
            accelerator = KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN)
            setOnAction { showSettings() }
        }
    val closeProject =
        MenuItem("Close Project").apply {
            setOnAction { stage.fireEvent(WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)) }
        }
    val recent =
        recentProjectsMenu({ recentEntries(recentProjects.list(), editorArea.projectFolder()) }, openRecent)
    val opening = scratchItems + listOf(SeparatorMenuItem(), openFolder, openProject, recent)
    return Menu(
        "File",
        null,
        *opening.toTypedArray(),
        SeparatorMenuItem(),
        save,
        saveAll,
        SeparatorMenuItem(),
        closeTab,
        SeparatorMenuItem(),
        settings,
        SeparatorMenuItem(),
        closeProject,
    )
}
