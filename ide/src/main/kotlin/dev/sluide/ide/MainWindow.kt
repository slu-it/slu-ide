package dev.sluide.ide

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.commandruns.CommandRunSession
import dev.sluide.dockersandbox.DockerSandbox
import dev.sluide.editorarea.EditorArea
import dev.sluide.editorarea.askToClose
import dev.sluide.fileexplorer.ExplorerBar
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileexplorer.FolderAction
import dev.sluide.fileexplorer.FolderActionResult
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.IgnoreRuleEffects
import dev.sluide.git.GitRemotes
import dev.sluide.git.GitStatusService
import dev.sluide.git.GitTrustCheck
import dev.sluide.git.RemoteSourceCache
import dev.sluide.githubactions.RemoteSources
import dev.sluide.gitlog.GitLogView
import dev.sluide.gradle.GradleSession
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.httpclient.HttpClientSetup
import dev.sluide.ide.git.WindowGitLog
import dev.sluide.ide.git.ignoreRuleEffects
import dev.sluide.ide.git.markers
import dev.sluide.ide.git.textDiff
import dev.sluide.ide.git.wouldCommit
import dev.sluide.ide.github.RemoteCache
import dev.sluide.ide.github.WindowWorkflowSources
import dev.sluide.ide.logging.currentLogFolder
import dev.sluide.ide.scratches.WindowScratches
import dev.sluide.ide.scripts.WindowScriptRuns
import dev.sluide.ide.tools.WindowTools
import dev.sluide.ide.trust.WindowTrust
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.projects.RecentProjects
import dev.sluide.projecttrust.ProjectTrust
import dev.sluide.scratches.ScratchFiles
import dev.sluide.settings.SettingsDialog
import dev.sluide.settings.SettingsPages
import dev.sluide.settings.ThemedWindows
import dev.sluide.terminal.Terminals
import javafx.beans.value.ObservableValue
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
    remoteSourceCache: RemoteSourceCache,
    gitRemotes: GitRemotes,
    private val gradleSession: GradleSession,
    private val commandRuns: CommandRunSession,
    private val projectData: ProjectDataStore,
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

    /**
     * The remote files of the workflow graphs (ADR 0076): from the shared cache, the origin's host first if the
     * folder's trust allows git, which reads the origin's URL. Readers run off the FX thread, so they may wait for the
     * trust checks of a folder just opened, as a graph restored with the window would otherwise miss its origin, until
     * the reader's view is edited or closed.
     */
    private val workflowSources =
        WindowWorkflowSources(
            RemoteCache.of(remoteSourceCache),
            folder = { folder },
            trusted = { cancelled -> projectTrust.awaitAllows(GitTrustCheck.ID, cancelled) },
            originUrl = { gitRemotes.url(it) },
        )

    /** Which lines of `.gitignore` files have an effect, as git decides (ADR 0080). */
    private val ignoreRuleEffects = gitStatus.ignoreRuleEffects()

    /** The explorer's Run for files their view can run, e.g. shell scripts (ADR 0089). */
    private val scriptRuns = WindowScriptRuns(fileViewers::viewerFor, { editorArea.open(it) }, editorArea::viewOf)

    /** The Terminal and Docker Sandbox menus and the tabs they open. */
    internal val tools =
        WindowTools(projectService, terminals, dockerSandbox, editorArea, showError = { s, m -> showError(s, m) })

    fun show(stage: Stage) {
        themedWindows.install()
        val explorerBar =
            ExplorerBar(fileExplorer, editorArea.selectedFile, ::selectOpenedFile, selectOpenedKey.displayText)
        val left = leftPane(explorerBar, fileExplorer, backgroundTasks)
        // Resizing the window resizes the editor area; the explorer keeps its width until the user drags the divider
        // (or the editor area is as narrow as it can be).
        val split = SplitPane(left, editorArea).apply { SplitPane.setResizableWithParent(left, false) }
        val root =
            BorderPane().apply {
                top =
                    VBox(
                        MenuBar(
                            fileMenu(stage),
                            viewMenu(explorerBar.canSelectOpened),
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
        editorArea.tabMenu.selectInExplorer = { fileExplorer.reveal(it, focus = true) }
        editorArea.tabMenu.canSelectInExplorer = fileExplorer::canReveal
        fileExplorer.canOpenFile = editorArea::canOpen
        // Per-file project data (run-argument histories, ADR 0091) follows files and folders renamed or moved in the
        // explorer, also of files not open; re-keyed first, so a view that follows reads its data under the new path.
        // Tabs follow instead of closing as if they were deleted.
        fileExplorer.onMoved = { from, to ->
            projectData.moved(from, to, folder)
            editorArea.moved(from, to)
        }
        // Deleting warns about unsaved changes and, once done, forgets the per-file project data below the path and
        // closes the tabs without asking again: clean ones and those it warned about.
        fileExplorer.unsavedUnder = editorArea::dirtyUnder
        fileExplorer.onDeleted = { path, unsaved ->
            projectData.deleted(path, folder)
            editorArea.closeUnder(path, unsaved)
        }
        // A folder's context menu creates the files to start with the HTTP client there (ADR 0056).
        fileExplorer.folderActions =
            listOf(
                FolderAction("New HTTP Client Setup") { folder ->
                    HttpClientSetup.create(folder).let { FolderActionResult(it.example, it.note) }
                },
            )
        // A file's context menu runs it where its view can (ADR 0089).
        fileExplorer.fileActions = listOf(scriptRuns.action)
        // A context menu's Open in Terminal opens a terminal tab in the folder (ADR 0093).
        fileExplorer.openInTerminal = { tools.openTerminal(stage, it) }
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
        // register their work as background tasks (ADR 0061); workflow graphs read remote references (ADR 0076);
        // .gitignore views show which of their lines have an effect (ADR 0080).
        editorArea.windowServices.register(HttpClientSession::class.java, httpSession)
        editorArea.windowServices.register(BackgroundTasks::class.java, backgroundTasks)
        editorArea.windowServices.register(RemoteSources::class.java, workflowSources)
        editorArea.windowServices.register(IgnoreRuleEffects::class.java, ignoreRuleEffects)
        // Views of build files share the task lists of their builds; a saved build file marks its build's list stale,
        // and Review… of a view whose Gradle is off asks about the folder's trust (ADR 0085).
        editorArea.windowServices.register(GradleSession::class.java, gradleSession)
        editorArea.onFileSaved = gradleSession::fileSaved
        // Views that run commands (shell scripts) run them in the window's terminals and list them among its
        // background tasks (ADR 0088, ADR 0089).
        editorArea.windowServices.register(CommandRunSession::class.java, commandRuns)
        gradleSession.reviewTrust = { trust.review(stage) }
        // It warns about private environment files that git would commit.
        httpSession.wouldCommit = { gitStatus.snapshot.get().wouldCommit(it) }
        gitStatus.snapshot.subscribe { snapshot ->
            stage.title = windowTitle(folder, snapshot.head)
            fileExplorer.refreshMarkers()
            editorArea.refreshDiffs()
            httpSession.gitStatusChanged()
            ignoreRuleEffects.changed()
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

    private val selectOpenedKey = KeyCodeCombination(KeyCode.F1, KeyCombination.ALT_DOWN)

    private fun viewMenu(canSelectOpened: ObservableValue<Boolean>): Menu {
        // Switches between the editor of the selected file and its diff against HEAD (ADR 0021).
        val showChanges =
            MenuItem("Show Changes").apply {
                accelerator = KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
                disableProperty().bind(editorArea.canToggleDiff.map { !it })
                setOnAction { editorArea.toggleDiff() }
            }
        // Reveals the selected tab's file in the explorer and focuses it (ADR 0095); Alt is Option on macOS.
        val selectOpened =
            MenuItem("Select Opened File").apply {
                accelerator = selectOpenedKey
                disableProperty().bind(canSelectOpened.map { !it })
                setOnAction { selectOpenedFile() }
            }
        return Menu("View", null, showChanges, selectOpened, gitLog.gitLogMenuItem())
    }

    /** Reveals the file of the selected tab in the explorer, with the focus; nothing without a file under a root. */
    private fun selectOpenedFile() {
        editorArea.selectedFile.value?.let { fileExplorer.reveal(it, focus = true) }
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
