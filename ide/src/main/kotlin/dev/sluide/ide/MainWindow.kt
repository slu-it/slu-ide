package dev.sluide.ide

import dev.sluide.editor.EditorArea
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.settings.SettingsDialog
import dev.sluide.settings.SettingsPages
import dev.sluide.settings.ThemedWindows
import dev.sluide.terminal.Terminals
import javafx.scene.Scene
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.SplitPane
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.layout.BorderPane
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import org.springframework.stereotype.Component
import java.nio.file.Path

private const val APP_NAME = "SluIde"

/** The main IDE window: menu bar on top, file explorer on the left, open files and terminals in tabs on the right. */
@Component
class MainWindow(
    private val projectService: ProjectService,
    private val fileExplorer: FileExplorerView,
    private val editorArea: EditorArea,
    private val terminals: Terminals,
    private val settingsPages: SettingsPages,
    private val themedWindows: ThemedWindows,
) {
    /** Shows the settings dialog and waits until it is closed; tests replace it, since it blocks. */
    internal var showSettings: (Stage) -> Unit = { stage -> SettingsDialog(settingsPages.pages, stage).showAndWait() }

    fun show(stage: Stage) {
        themedWindows.install()
        val root =
            BorderPane().apply {
                top = MenuBar(fileMenu(stage), terminalMenu())
                center = SplitPane(fileExplorer, editorArea).apply { setDividerPositions(0.25) }
            }

        fileExplorer.onOpenFile = { editorArea.open(it) }

        // Fires only when the folder really changes, so reopening the current folder keeps the open tabs.
        projectService.projectFolder.subscribe { folder ->
            editorArea.closeAll()
            fileExplorer.open(folder)
            stage.title = folder?.let { "${it.fileName ?: it} – $APP_NAME" } ?: APP_NAME
        }

        stage.scene = Scene(root, 1000.0, 700.0)
        stage.show()
    }

    private fun fileMenu(stage: Stage): Menu {
        val openFolder =
            MenuItem("Open Folder…").apply {
                accelerator = KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN)
                setOnAction { chooseFolder(stage) }
            }
        val settings =
            MenuItem("Settings…").apply {
                accelerator = KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN)
                setOnAction { showSettings(stage) }
            }
        return Menu("File", null, openFolder, SeparatorMenuItem(), settings)
    }

    private fun terminalMenu(): Menu {
        val newTerminal =
            MenuItem("New Terminal").apply {
                // Ctrl+Shift+letter is not sent to the shell, so this also works while a terminal has the focus.
                accelerator = KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
                setOnAction { openTerminal() }
            }
        return Menu("Terminal", null, newTerminal)
    }

    /** Opens a terminal tab in the project folder, or in the home folder if no project is open. */
    internal fun openTerminal() {
        val folder = projectService.projectFolder.get() ?: Path.of(System.getProperty("user.home"))
        editorArea.open(terminals.open(folder))
    }

    private fun chooseFolder(stage: Stage) {
        val chooser =
            DirectoryChooser().apply {
                title = "Open Folder"
                projectService.projectFolder.get()?.let { initialDirectory = it.toFile() }
            }
        chooser.showDialog(stage)?.let { projectService.open(it.toPath()) }
    }
}
