package dev.sluide.ide.tools

import dev.sluide.coloring.Coloring
import dev.sluide.dockersandbox.DockerSandbox
import dev.sluide.dockersandbox.SandboxAction
import dev.sluide.dockersandbox.SandboxNotAllowedException
import dev.sluide.editorarea.EditorArea
import dev.sluide.ide.ProjectService
import dev.sluide.terminal.TerminalStartException
import dev.sluide.terminal.Terminals
import javafx.application.Platform
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.stage.Stage
import java.nio.file.Path

/**
 * The tools of one window: the Terminal and Docker Sandbox menus with the terminal tabs they open in the
 * [editorArea], and the Tools menu with the Coloring tab (ADR 0110). [showError] shows why a tool cannot be started. Used on the JavaFX thread.
 */
internal class WindowTools(
    private val projectService: ProjectService,
    /** The window's terminals; tests read them to check what was opened. */
    internal val terminals: Terminals,
    private val dockerSandbox: DockerSandbox,
    private val editorArea: EditorArea,
    coloring: Coloring,
    private val showError: (Stage, String) -> Unit,
) {
    /** The Coloring tool of the Tools menu. */
    internal val coloringTool = WindowTool("Coloring", coloring::createTab)

    /** The Tools menu logic; tests choose tools through it. */
    internal val toolsMenu =
        ToolsMenu({ editorArea.tabs }, editorArea::open, editorArea::select, listOf(coloringTool))

    init {
        coloring.showTab = { toolsMenu.open(coloringTool) }
        coloring.projectFolder.bind(projectService.projectFolder)
    }

    /** The Tools menu: Coloring. */
    fun toolsMenu(): Menu = toolsMenu.menu()

    /** The Terminal menu: New Terminal. */
    fun terminalMenu(stage: Stage): Menu {
        val newTerminal =
            MenuItem("New Terminal").apply {
                // Ctrl+Shift+letter is not sent to the shell, so this also works while a terminal has the focus
                // (unlike Ctrl+letter shortcuts such as Ctrl+O on Linux, which the terminal sends to the shell).
                accelerator = KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
                setOnAction { openTerminal(stage) }
            }
        return Menu("Terminal", null, newTerminal)
    }

    /**
     * Opens a terminal tab in [folder], by default the project folder, or the home folder if no project is open; shows
     * an error if the shell cannot be started.
     */
    internal fun openTerminal(
        stage: Stage,
        folder: Path = projectService.projectFolder.get() ?: Path.of(System.getProperty("user.home")),
    ) {
        try {
            editorArea.open(terminals.open(folder))
        } catch (e: TerminalStartException) {
            showError(stage, "The terminal cannot be started. ${e.message}")
        }
    }

    /**
     * The sandbox actions; each is enabled only where it may run, which is checked again when it is started. The whole
     * menu is disabled until the check for `sbx` has found it.
     */
    fun dockerSandboxMenu(stage: Stage): Menu {
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
            isDisable = true
            dockerSandbox.sbxInstalled.thenAccept { installed ->
                Platform.runLater {
                    isDisable = !installed
                    updateItems()
                }
            }
        }
    }

    private fun openSandbox(
        stage: Stage,
        action: SandboxAction,
    ) {
        fun cannotStart(e: RuntimeException) = showError(stage, "${action.title} cannot be started. ${e.message}")
        try {
            editorArea.open(dockerSandbox.open(action, projectService.projectFolder.get()))
        } catch (e: SandboxNotAllowedException) {
            cannotStart(e)
        } catch (e: TerminalStartException) {
            cannotStart(e)
        }
    }
}
