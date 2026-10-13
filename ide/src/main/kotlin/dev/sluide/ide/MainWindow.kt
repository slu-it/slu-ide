package dev.sluide.ide

import dev.sluide.dockersandbox.DockerSandbox
import dev.sluide.dockersandbox.SandboxAction
import dev.sluide.dockersandbox.SandboxNotAllowedException
import dev.sluide.editor.EditorArea
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.git.GitStatusService
import dev.sluide.settings.SettingsDialog
import dev.sluide.settings.SettingsPages
import dev.sluide.settings.ThemedWindows
import dev.sluide.terminal.Terminals
import javafx.scene.Scene
import javafx.scene.control.Alert
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
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path

private const val APP_NAME = "SluIde"

/** The main IDE window: menu bar on top, file explorer on the left, open files and terminals in tabs on the right. */
@Component
@Scope(WINDOW_SCOPE)
class MainWindow(
    private val projectService: ProjectService,
    private val fileExplorer: FileExplorerView,
    private val editorArea: EditorArea,
    internal val terminals: Terminals,
    private val dockerSandbox: DockerSandbox,
    private val gitStatus: GitStatusService,
    private val settingsPages: SettingsPages,
    private val themedWindows: ThemedWindows,
    private val projectWindows: ProjectWindows,
) {
    /** Shows the settings dialog and waits until it is closed; tests replace it, since it blocks. */
    internal var showSettings: (Stage) -> Unit = { stage -> SettingsDialog(settingsPages.pages, stage).showAndWait() }

    /** Shows an error message and waits until it is closed; tests replace it, since it blocks. */
    internal var showError: (Stage, String) -> Unit = { stage, message ->
        Alert(Alert.AlertType.ERROR, message)
            .apply {
                initOwner(stage)
                headerText = null
            }.showAndWait()
    }

    /** The project folder of this window; `null` until a folder is opened in it. */
    val folder: Path? get() = projectService.projectFolder.get()

    fun show(stage: Stage) {
        themedWindows.install()
        val root =
            BorderPane().apply {
                top = MenuBar(fileMenu(stage), terminalMenu(), dockerSandboxMenu(stage))
                center = SplitPane(fileExplorer, editorArea).apply { setDividerPositions(0.25) }
            }

        fileExplorer.onOpenFile = { editorArea.open(it) }
        fileExplorer.canOpenFile = editorArea::canOpen
        // Git status markers, if git is installed and the folder is a repository (ADR 0013).
        fileExplorer.markers = { path, directory -> gitStatus.snapshot.get().markers(path, directory) }
        fileExplorer.onFilesChanged = gitStatus::refresh
        gitStatus.snapshot.subscribe { _ -> fileExplorer.refreshMarkers() }

        // A window gets its folder once (ADR 0014); other folders open in new windows.
        projectService.projectFolder.subscribe { folder ->
            fileExplorer.open(folder)
            gitStatus.open(folder)
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

    /** The sandbox actions; each is enabled only where it may run, which is checked again when it is started. */
    private fun dockerSandboxMenu(stage: Stage): Menu {
        val items =
            SandboxAction.entries.associateWith { action ->
                MenuItem(action.title).apply { setOnAction { openSandbox(stage, action) } }
            }

        fun updateItems() =
            items.forEach { (action, item) ->
                item.isDisable = dockerSandbox.problem(action, projectService.projectFolder.get()) != null
            }
        projectService.projectFolder.subscribe { _ -> updateItems() }
        return Menu("Docker Sandbox", null, *items.values.toTypedArray()).apply {
            // Also picks up an sbxenv.yaml created or deleted since the folder was opened.
            setOnShowing { updateItems() }
        }
    }

    private fun openSandbox(
        stage: Stage,
        action: SandboxAction,
    ) {
        val folder = projectService.projectFolder.get()
        try {
            if (folder == null) throw SandboxNotAllowedException("No folder is open.")
            editorArea.open(dockerSandbox.open(action, folder))
        } catch (e: SandboxNotAllowedException) {
            showError(stage, "${action.title} cannot be started. ${e.message}")
        }
    }

    private fun chooseFolder(stage: Stage) {
        val chooser =
            DirectoryChooser().apply {
                title = "Open Folder"
                projectService.projectFolder.get()?.let { initialDirectory = it.toFile() }
            }
        chooser.showDialog(stage)?.let { openFolder(it.toPath()) }
    }

    /**
     * Opens [folder] in this window if it has no folder yet; otherwise in another window ([ProjectWindows.open]),
     * which is brought to the front if it already shows [folder].
     */
    internal fun openFolder(folder: Path) {
        if (this.folder == null) projectService.open(folder) else projectWindows.open(folder)
    }

    /** Closes all tabs (ending their terminals) and stops watching the folder; called when the window is closed. */
    fun dispose() {
        editorArea.closeAll()
        fileExplorer.open(null)
        gitStatus.open(null)
    }
}
