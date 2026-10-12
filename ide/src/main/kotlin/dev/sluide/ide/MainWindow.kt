package dev.sluide.ide

import dev.sluide.editor.EditorArea
import dev.sluide.fileexplorer.FileExplorerView
import javafx.scene.Scene
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.control.SplitPane
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.layout.BorderPane
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import org.springframework.stereotype.Component

private const val APP_NAME = "SluIde"

/** The main IDE window: menu bar on top, file explorer on the left, open files in tabs on the right. */
@Component
class MainWindow(
    private val projectService: ProjectService,
    private val fileExplorer: FileExplorerView,
    private val editorArea: EditorArea,
) {
    fun show(stage: Stage) {
        val root =
            BorderPane().apply {
                top = MenuBar(fileMenu(stage))
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
        return Menu("File", null, openFolder)
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
