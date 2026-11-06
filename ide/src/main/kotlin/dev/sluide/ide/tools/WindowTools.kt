package dev.sluide.ide.tools

import dev.sluide.codecs.Codecs
import dev.sluide.coloring.Coloring
import dev.sluide.converting.Converting
import dev.sluide.databases.Databases
import dev.sluide.docker.Docker
import dev.sluide.dockersandbox.DockerSandbox
import dev.sluide.dockersandbox.SandboxAction
import dev.sluide.dockersandbox.SandboxNotAllowedException
import dev.sluide.editorarea.EditorArea
import dev.sluide.games.Minesweeper
import dev.sluide.games.WordGuess
import dev.sluide.ide.ProjectService
import dev.sluide.ide.isMac
import dev.sluide.speech.ReadAloud
import dev.sluide.terminal.TerminalStartException
import dev.sluide.terminal.Terminals
import dev.sluide.translation.Translate
import javafx.application.Platform
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.stage.Stage
import java.net.URI
import java.nio.file.Path

/**
 * The tools of one window: the Terminal and Docker Sandbox menus with the terminal tabs they open in the
 * [editorArea], the Tools menu with the Coloring, Codecs, Converting, Read Aloud and Translate tabs (ADR 0110,
 * ADR 0112, ADR 0115, ADR 0124, ADR 0126), the Services menu with the Docker and Databases tabs (ADR 0118,
 * ADR 0121) and the Games menu with the Minesweeper and Word Guess tabs (ADR 0131, 0132). [showError] shows why a
 * tool cannot be started.
 * Used on the JavaFX thread.
 */
internal class WindowTools(
    private val projectService: ProjectService,
    /** The window's terminals; tests read them to check what was opened. */
    internal val terminals: Terminals,
    private val dockerSandbox: DockerSandbox,
    private val editorArea: EditorArea,
    coloring: Coloring,
    codecs: Codecs,
    converting: Converting,
    readAloud: ReadAloud,
    translate: Translate,
    docker: Docker,
    databases: Databases,
    minesweeper: Minesweeper,
    wordGuess: WordGuess,
    /** Opens a web page in the system browser. */
    openWebPage: (URI) -> Unit,
    private val showError: (Stage, String) -> Unit,
) {
    /** The Coloring tool of the Tools menu. */
    internal val coloringTool = WindowTool("Coloring", coloring::createTab)

    /** The Codecs tool of the Tools menu. */
    internal val codecsTool = WindowTool("Codecs", codecs::createTab)

    /** The Converting tool of the Tools menu. */
    internal val convertingTool = WindowTool("Converting", converting::createTab)

    /** The Read Aloud tool of the Tools menu. */
    internal val readAloudTool = WindowTool("Read Aloud", readAloud::createTab)

    /** The Translate tool of the Tools menu. */
    internal val translateTool = WindowTool("Translate", translate::createTab, newTabEachTime = true)

    /** The Tools menu logic; tests choose tools through it. */
    internal val toolsMenu =
        ToolsMenu(
            { editorArea.tabs },
            editorArea::open,
            editorArea::select,
            listOf(coloringTool, codecsTool, convertingTool, readAloudTool, translateTool),
        )

    /** The Docker tool of the Services menu, on IntelliJ's key for its Services tool window. */
    internal val dockerTool =
        WindowTool(
            "Docker",
            docker::createTab,
            KeyCodeCombination(KeyCode.DIGIT8, if (isMac) KeyCombination.SHORTCUT_DOWN else KeyCombination.ALT_DOWN),
        )

    /** The Databases tool of the Services menu, on the key next to Docker's. */
    internal val databasesTool =
        WindowTool(
            "Databases",
            databases::createTab,
            KeyCodeCombination(KeyCode.DIGIT7, if (isMac) KeyCombination.SHORTCUT_DOWN else KeyCombination.ALT_DOWN),
        )

    /** The Services menu logic; tests choose tools through it. */
    internal val servicesMenu =
        ToolsMenu(
            { editorArea.tabs },
            editorArea::open,
            editorArea::select,
            listOf(dockerTool, databasesTool),
            "Services",
        )

    /** The Minesweeper tool of the Games menu; it has no key binding. */
    internal val minesweeperTool = WindowTool("Minesweeper", minesweeper::createTab)

    /** The Word Guess tool of the Games menu; it has no key binding. */
    internal val wordGuessTool = WindowTool("Word Guess", wordGuess::createTab)

    /** The Games menu logic; tests choose tools through it. */
    internal val gamesMenu =
        ToolsMenu(
            { editorArea.tabs },
            editorArea::open,
            editorArea::select,
            listOf(minesweeperTool, wordGuessTool),
            "Games",
        )

    init {
        coloring.showTab = { toolsMenu.open(coloringTool) }
        codecs.showTab = { toolsMenu.open(codecsTool) }
        converting.showTab = { toolsMenu.open(convertingTool) }
        readAloud.showTab = { toolsMenu.open(readAloudTool) }
        translate.openTab = { editorArea.open(it) }
        coloring.projectFolder.bind(projectService.projectFolder)
        docker.showTab = { servicesMenu.open(dockerTool) }
        docker.openTab = { editorArea.open(it) }
        docker.openUrl = openWebPage
        docker.projectFolder.bind(projectService.projectFolder)
        databases.showTab = { servicesMenu.open(databasesTool) }
        databases.openTab = { editorArea.open(it) }
        databases.projectFolder.bind(projectService.projectFolder)
        minesweeper.showTab = { gamesMenu.open(minesweeperTool) }
        wordGuess.showTab = { gamesMenu.open(wordGuessTool) }
    }

    /** The Tools menu: Coloring, Codecs, Converting, Read Aloud, Translate. */
    fun toolsMenu(): Menu = toolsMenu.menu()

    /** The Services menu: Docker, Databases. */
    fun servicesMenu(): Menu = servicesMenu.menu()

    /** The Games menu: Minesweeper and Word Guess. */
    fun gamesMenu(): Menu = gamesMenu.menu()

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
