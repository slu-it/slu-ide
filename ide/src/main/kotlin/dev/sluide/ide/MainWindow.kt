package dev.sluide.ide

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.dockersandbox.DockerSandbox
import dev.sluide.editorarea.EditorArea
import dev.sluide.editorarea.askToClose
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileexplorer.FolderAction
import dev.sluide.fileexplorer.FolderActionResult
import dev.sluide.fileviewers.FileViewers
import dev.sluide.git.GitStatusService
import dev.sluide.gitlog.GitLogView
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.httpclient.HttpClientSetup
import dev.sluide.ide.git.WindowGitLog
import dev.sluide.ide.git.markers
import dev.sluide.ide.git.textDiff
import dev.sluide.ide.git.wouldCommit
import dev.sluide.ide.logging.currentLogFolder
import dev.sluide.ide.scratches.WindowScratches
import dev.sluide.ide.tools.WindowTools
import dev.sluide.ide.trust.WindowTrust
import dev.sluide.projects.RecentProjects
import dev.sluide.projecttrust.ProjectTrust
import dev.sluide.scratches.ScratchFiles
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
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.net.URI
import java.nio.file.Path

/**
 * The main IDE window: menu bar on top, file explorer on the left, open files and terminals in tabs on the right, and
 * for git repositories the git log below them. It coordinates the window parts of the features (ADR 0068): the layout,
 * the wiring between explorer and editor, the folder, closing, the File menu and the menu bar.
 */
@Component
@Scope(WINDOW_SCOPE)
class MainWindow(
    private val projectService: ProjectService,
    private val fileExplorer: FileExplorerView,
    private val editorArea: EditorArea,
    terminals: Terminals,
    dockerSandbox: DockerSandbox,
    private val gitStatus: GitStatusService,
    gitLogView: GitLogView,
    fileViewers: FileViewers,
    private val settingsPages: SettingsPages,
    private val themedWindows: ThemedWindows,
    private val projectWindows: ProjectWindows,
    private val httpSession: HttpClientSession,
    private val recentProjects: RecentProjects,
    private val projectTrust: ProjectTrust,
    private val backgroundTasks: BackgroundTasks,
    scratchFiles: ScratchFiles,
    systemBrowser: SystemBrowser,
) {
    /** Shows a web page in the system browser; tests replace it. */
    internal var openWebPage: (URI) -> Unit = systemBrowser::open

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

    /**
     * Asked before the window is closed while tabs still run something, with their warnings; returns whether to close
     * it. Shows a confirmation dialog; tests replace it, since it blocks.
     */
    internal var confirmCloseWindow: (Stage, List<String>) -> Boolean = { stage, warnings ->
        askToClose(stage, "Close Window", "Close this window anyway?", warnings)
    }

    /** The project folder of this window; `null` until a folder is opened in it. */
    val folder: Path? get() = projectService.projectFolder.get()

    /** The files open in this window's tabs. */
    internal val openPaths: List<Path> get() = editorArea.openPaths

    /** The shared scratch files in this window's explorer and editor (ADR 0066). */
    internal val scratches =
        WindowScratches(
            scratchFiles,
            fileExplorer,
            editorArea,
            isOpenElsewhere = { projectWindows.isOpenElsewhere(it, this) },
            showError = { stage, message -> showError(stage, message) },
        )

    /** The trust bar and the questions behind it (ADR 0053). */
    internal val trust = WindowTrust(projectTrust, folder = { folder })

    /** The git log below the editor area, the Git menu and their wiring (ADRs 0035, 0053). */
    private val gitLog = WindowGitLog(gitLogView, gitStatus, projectTrust, editorArea, fileExplorer, fileViewers)

    /** The Terminal and Docker Sandbox menus and the tabs they open. */
    internal val tools =
        WindowTools(projectService, terminals, dockerSandbox, editorArea, showError = { s, m -> showError(s, m) })

    fun show(stage: Stage) {
        themedWindows.install()
        val left = leftPane(fileExplorer, backgroundTasks)
        // Resizing the window resizes the editor area; the explorer keeps its width until the user drags the divider
        // (or the editor area is as narrow as it can be).
        val split = SplitPane(left, editorArea).apply { SplitPane.setResizableWithParent(left, false) }
        val root =
            BorderPane().apply {
                top =
                    VBox(
                        MenuBar(
                            fileMenu(stage),
                            viewMenu(),
                            gitLog.gitMenu(),
                            tools.terminalMenu(stage),
                            tools.dockerSandboxMenu(stage),
                        ),
                        trust.bar,
                    )
                center = split
                stylesheets += checkNotNull(MainWindow::class.java.getResource("main-window.css")).toExternalForm()
            }

        fileExplorer.onOpenFile = { editorArea.open(it) }
        fileExplorer.canOpenFile = editorArea::canOpen
        // Tabs follow files and folders renamed in the explorer instead of closing as if they were deleted.
        fileExplorer.onMoved = editorArea::moved
        // Deleting warns about unsaved changes and, once done, closes the tabs without asking again: clean ones and
        // those it warned about.
        fileExplorer.unsavedUnder = editorArea::dirtyUnder
        fileExplorer.onDeleted = editorArea::closeUnder
        // A folder's context menu creates the files to start with the HTTP client there (ADR 0056).
        fileExplorer.folderActions =
            listOf(
                FolderAction("New HTTP Client Setup") { folder ->
                    HttpClientSetup.create(folder).let { FolderActionResult(it.example, it.note) }
                },
            )
        // Git status markers, if git is installed and the folder is a repository (ADR 0013).
        fileExplorer.markers = { path, directory -> gitStatus.snapshot.get().markers(path, directory) }
        // Not for the log file growing inside the folder: the status's own log lines would trigger the next refresh.
        // Nor for scratch files, which are normally in no repository (ADR 0066).
        fileExplorer.onFilesChanged = { changed ->
            if (!currentLogFolder.onlyLogFiles(changed) && !scratches.onlyScratches(changed)) gitStatus.refresh()
        }
        // The changes of modified files against HEAD are marked in their editors, updated with the status (ADR 0021).
        editorArea.diffOf = gitStatus::textDiff
        // Links in file views (e.g. the Markdown preview) to web pages open in the system browser.
        editorArea.openWebPage = { openWebPage(it) }
        // HTML previews are served with the project's files, and only those (ADR 0018).
        editorArea.projectFolder = { folder }
        editorArea.showError = { showError(stage, it) }
        // An empty scratch file is deleted when its last tab in any window closes (ADR 0066).
        editorArea.onFileClosed = scratches::fileClosed
        // Views of .http files share the window's cookies, tokens and chosen environments (ADR 0026); file views may
        // register their work as background tasks (ADR 0061).
        editorArea.windowServices.register(HttpClientSession::class.java, httpSession)
        editorArea.windowServices.register(BackgroundTasks::class.java, backgroundTasks)
        // It warns about private environment files that git would commit.
        httpSession.wouldCommit = { gitStatus.snapshot.get().wouldCommit(it) }
        gitStatus.snapshot.subscribe { snapshot ->
            stage.title = windowTitle(folder, snapshot.head)
            fileExplorer.refreshMarkers()
            editorArea.refreshDiffs()
            httpSession.gitStatusChanged()
            // The Commit tab lists what can be committed (ADR 0048).
            gitLog.workingTreeChanged()
        }

        // Git runs only while the folder's trust allows it (ADR 0053), after its checks or the user's decision.
        projectTrust.state.subscribe { state ->
            gitLog.followTrust()
            trust.follow(stage, state)
        }
        trust.attach(stage)

        gitLog.wire()

        // A window gets its folder once (ADR 0014); other folders open in new windows.
        projectService.projectFolder.subscribe { folder ->
            // The scratches below the project, only once there is one (ADR 0066).
            fileExplorer.open(folder, if (folder != null) scratches.roots() else emptyList())
            // Tools run only what a trusted folder defines; git's status waits for the checks (ADR 0053).
            projectTrust.open(folder)
            gitStatus.open(folder)
            gitLog.show(split, gitStatus.active)
            gitLog.followTrust()
            fitExplorer(split, fileExplorer)
            stage.title = windowTitle(folder, gitStatus.snapshot.get().head)
            // Every opened folder is remembered for the project picker: chosen there, with Open Folder… or given on
            // the command line (ADR 0034).
            folder?.let { recentProjects.opened(it) }
        }

        // Closing the window first asks to save its unsaved files, then, as it ends its terminals and commands, asks
        // once, listing all of them. Quitting on macOS (Cmd+Q) asks every window to close the same way.
        stage.setOnCloseRequest { event ->
            if (!editorArea.confirmCloseAll()) {
                event.consume()
                return@setOnCloseRequest
            }
            val warnings = editorArea.tabs.mapNotNull { it.closeWarning }
            if (warnings.isNotEmpty() && !confirmCloseWindow(stage, warnings)) event.consume()
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
        // The project picker, to open a remembered project in another window (ADR 0034).
        val openProject = MenuItem("Open Project…").apply { setOnAction { projectWindows.pickProject() } }
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
        // Only file and diff tabs close; a terminal keeps Ctrl+W for the shell, and Cmd+W on macOS finds the item
        // disabled.
        val closeTab =
            MenuItem("Close Tab").apply {
                accelerator = KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN)
                disableProperty().bind(editorArea.canCloseSelected.map { !it })
                setOnAction { editorArea.closeSelected() }
            }
        val settings =
            MenuItem("Settings…").apply {
                accelerator = KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN)
                setOnAction { showSettings(stage) }
            }
        val opening = arrayOf(scratches.menuItem(stage), SeparatorMenuItem(), openFolder, openProject)
        return Menu(
            "File",
            null,
            *opening,
            SeparatorMenuItem(),
            save,
            saveAll,
            SeparatorMenuItem(),
            closeTab,
            SeparatorMenuItem(),
            settings,
        )
    }

    private fun viewMenu(): Menu {
        // Switches between the editor of the selected file and its diff against HEAD (ADR 0021).
        val showChanges =
            MenuItem("Show Changes").apply {
                accelerator = KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
                disableProperty().bind(editorArea.canToggleDiff.map { !it })
                setOnAction { editorArea.toggleDiff() }
            }
        return Menu("View", null, showChanges, gitLog.gitLogMenuItem())
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
        gitLog.dispose()
        projectTrust.open(null)
    }
}
