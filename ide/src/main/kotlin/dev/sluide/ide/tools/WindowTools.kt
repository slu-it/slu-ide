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
import dev.sluide.games.BlockBreaker
import dev.sluide.games.Doubles
import dev.sluide.games.Minesweeper
import dev.sluide.games.MiniGolf
import dev.sluide.games.OrbitGuard
import dev.sluide.games.PipePuzzle
import dev.sluide.games.Solitaire
import dev.sluide.games.Sudoku
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
 * ADR 0112, ADR 0115, ADR 0124, ADR 0126), the Services menu with the Databases and Docker tabs (ADR 0118,
 * ADR 0121) and the Games menu (both sorted by title) with the Block Breaker, Doubles, Minesweeper, Mini Golf, Orbit Guard, Pipe Puzzle, Solitaire, Sudoku and Word Guess tabs (ADR 0131, 0132, 0136, 0137, 0138, 0139, 0140, 0152, 0153).
 * [showError] shows why a tool cannot be started.
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
    doubles: Doubles,
    sudoku: Sudoku,
    solitaire: Solitaire,
    orbitGuard: OrbitGuard,
    blockBreaker: BlockBreaker,
    miniGolf: MiniGolf,
    pipePuzzle: PipePuzzle,
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
            listOf(dockerTool, databasesTool).sortedBy { it.title.lowercase() },
            "Services",
        )

    /** The Minesweeper tool of the Games menu; it has no key binding. */
    internal val minesweeperTool = WindowTool("Minesweeper", minesweeper::createTab, graphic = minesweeper::logo)

    /** The Word Guess tool of the Games menu; it has no key binding. */
    internal val wordGuessTool = WindowTool("Word Guess", wordGuess::createTab, graphic = wordGuess::logo)

    /** The Doubles tool of the Games menu; it has no key binding. */
    internal val doublesTool = WindowTool("Doubles", doubles::createTab, graphic = doubles::logo)

    /** The Sudoku tool of the Games menu; it has no key binding. */
    internal val sudokuTool = WindowTool("Sudoku", sudoku::createTab, graphic = sudoku::logo)

    /** The Solitaire tool of the Games menu; it has no key binding. */
    internal val solitaireTool = WindowTool("Solitaire", solitaire::createTab, graphic = solitaire::logo)

    /** The Orbit Guard tool of the Games menu; it has no key binding. */
    internal val orbitGuardTool = WindowTool("Orbit Guard", orbitGuard::createTab, graphic = orbitGuard::logo)

    /** The Block Breaker tool of the Games menu; it has no key binding. */
    internal val blockBreakerTool = WindowTool("Block Breaker", blockBreaker::createTab, graphic = blockBreaker::logo)

    /** The Mini Golf tool of the Games menu; it has no key binding. */
    internal val miniGolfTool = WindowTool("Mini Golf", miniGolf::createTab, graphic = miniGolf::logo)

    /** The Pipe Puzzle tool of the Games menu; it has no key binding. */
    internal val pipePuzzleTool = WindowTool("Pipe Puzzle", pipePuzzle::createTab, graphic = pipePuzzle::logo)

    /** The Games menu logic; tests choose tools through it. */
    internal val gamesMenu =
        ToolsMenu(
            { editorArea.tabs },
            editorArea::open,
            editorArea::select,
            listOf(
                minesweeperTool,
                wordGuessTool,
                doublesTool,
                sudokuTool,
                solitaireTool,
                orbitGuardTool,
                blockBreakerTool,
                miniGolfTool,
                pipePuzzleTool,
            ).sortedBy { it.title.lowercase() },
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
        doubles.showTab = { gamesMenu.open(doublesTool) }
        sudoku.showTab = { gamesMenu.open(sudokuTool) }
        solitaire.showTab = { gamesMenu.open(solitaireTool) }
        orbitGuard.showTab = { gamesMenu.open(orbitGuardTool) }
        blockBreaker.showTab = { gamesMenu.open(blockBreakerTool) }
        miniGolf.showTab = { gamesMenu.open(miniGolfTool) }
        pipePuzzle.showTab = { gamesMenu.open(pipePuzzleTool) }
    }

    /** The Tools menu: Coloring, Codecs, Converting, Read Aloud, Translate. */
    fun toolsMenu(): Menu = toolsMenu.menu()

    /** The Services menu, sorted by title: Databases, Docker. */
    fun servicesMenu(): Menu = servicesMenu.menu()

    /** The Games menu, sorted by title: Block Breaker, Doubles, Minesweeper, Mini Golf, Orbit Guard, Pipe Puzzle, Solitaire, Sudoku, Word Guess. */
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
     * Runs [command] in a new terminal tab named [name] in [folder] (the code intelligence's environment banner,
     * ADR 0151); shows an error if the shell cannot be started.
     */
    internal fun runInTerminal(
        stage: Stage,
        folder: Path,
        name: String,
        command: String,
    ) {
        try {
            editorArea.open(terminals.open(folder, name, command))
        } catch (e: TerminalStartException) {
            showError(stage, "The terminal cannot be started. ${e.message}")
        }
    }

    /**
     * One item, "New Docker Sandbox Session (Environment)" where the project folder has an `sbxenv.yaml`, else
     * "(Claude Code)"; enabled only where that action may run. The action is chosen again when the item fires (the
     * accelerator works without the menu showing), and checked again when it is started. The whole menu is disabled
     * until the check for `sbx` has found it.
     */
    fun dockerSandboxMenu(stage: Stage): Menu {
        val item =
            MenuItem().apply {
                accelerator = KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
                setOnAction { openSandbox(stage, SandboxAction.forFolder(projectService.projectFolder.get())) }
            }

        fun updateItem() {
            val folder = projectService.projectFolder.get()
            val action = SandboxAction.forFolder(folder)
            item.text = "New Docker Sandbox Session (${action.title})"
            item.isDisable = dockerSandbox.problem(action, folder) != null
        }
        updateItem()
        // Fired before an accelerator checks isDisable, so Shortcut+Shift+B sees a fresh state too.
        item.setOnMenuValidation { updateItem() }
        projectService.projectFolder.subscribe { _ -> updateItem() }
        return Menu("Docker Sandbox", null, item).apply {
            // Also picks up an sbxenv.yaml created or deleted since the folder was opened.
            setOnShowing { updateItem() }
            isDisable = true
            dockerSandbox.sbxInstalled.thenAccept { installed ->
                Platform.runLater {
                    isDisable = !installed
                    updateItem()
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
