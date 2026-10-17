package dev.sluide.ide

import dev.sluide.dockersandbox.DockerSandbox
import dev.sluide.dockersandbox.SandboxAction
import dev.sluide.dockersandbox.SandboxNotAllowedException
import dev.sluide.editor.EditorArea
import dev.sluide.editor.askToClose
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileviewers.FileViewers
import dev.sluide.git.GitChange
import dev.sluide.git.GitHead
import dev.sluide.git.GitStatusService
import dev.sluide.gitlog.CommitFileDiff
import dev.sluide.gitlog.GitLogView
import dev.sluide.gitlog.WorkingTreeFileDiff
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.projects.RecentProjects
import dev.sluide.settings.SettingsDialog
import dev.sluide.settings.SettingsPages
import dev.sluide.settings.ThemedWindows
import dev.sluide.terminal.TerminalStartException
import dev.sluide.terminal.Terminals
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.beans.Observable
import javafx.beans.property.SimpleBooleanProperty
import javafx.geometry.Orientation
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
import javafx.scene.layout.Region
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

private const val APP_NAME = "Thoreau"

/** Alt+digit types characters on macOS, so window shortcuts with digits use Cmd there. */
private val isMac = System.getProperty("os.name").lowercase().contains("mac")

/** Where the divider above the git log starts when it is expanded: the log takes the lower 30 % (ADR 0035). */
private const val GIT_LOG_DIVIDER = 0.7

/**
 * The window title: the folder's name, the checked-out branch (or `detached at` and the short commit) in brackets if
 * [head] is known, and the app name; just the app name without a [folder].
 */
internal fun windowTitle(
    folder: Path?,
    head: GitHead?,
): String {
    if (folder == null) return APP_NAME
    val name = folder.fileName ?: folder
    val ref = head?.branch?.let { " [$it]" } ?: head?.shortCommit?.let { " [detached at $it]" } ?: ""
    return "$name$ref – $APP_NAME"
}

/**
 * The main IDE window: menu bar on top, file explorer on the left, open files and terminals in tabs on the right, and
 * for git repositories the git log below them.
 */
@Component
@Scope(WINDOW_SCOPE)
class MainWindow(
    private val projectService: ProjectService,
    private val fileExplorer: FileExplorerView,
    private val editorArea: EditorArea,
    internal val terminals: Terminals,
    private val dockerSandbox: DockerSandbox,
    private val gitStatus: GitStatusService,
    private val gitLogView: GitLogView,
    private val fileViewers: FileViewers,
    private val settingsPages: SettingsPages,
    private val themedWindows: ThemedWindows,
    private val projectWindows: ProjectWindows,
    private val httpSession: HttpClientSession,
    private val recentProjects: RecentProjects,
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

    /** Whether the git log is shown below the editor area: only for folders whose git status is followed. */
    private val gitLogPresent = SimpleBooleanProperty(false)

    /** The editor area above the git log, while it is present (ADR 0035). */
    private val gitLogSplit =
        SplitPane().apply {
            orientation = Orientation.VERTICAL
            // Resizing the window resizes the editor area; the log keeps its height.
            SplitPane.setResizableWithParent(gitLogView, false)
        }

    /** The height of the expanded git log in pixels, kept while it is collapsed; 0 until it was expanded. */
    private var gitLogHeight = 0.0

    fun show(stage: Stage) {
        themedWindows.install()
        // Resizing the window resizes the editor area; the explorer keeps its width until the user drags the divider
        // (or the editor area is as narrow as it can be).
        val split = SplitPane(fileExplorer, editorArea).apply { SplitPane.setResizableWithParent(fileExplorer, false) }
        val root =
            BorderPane().apply {
                top = MenuBar(fileMenu(stage), viewMenu(), gitMenu(), terminalMenu(stage), dockerSandboxMenu(stage))
                center = split
            }

        fileExplorer.onOpenFile = { editorArea.open(it) }
        fileExplorer.canOpenFile = editorArea::canOpen
        // Tabs follow files and folders renamed in the explorer instead of closing as if they were deleted.
        fileExplorer.onMoved = editorArea::moved
        // Deleting warns about unsaved changes and, once done, closes the tabs without asking again: clean ones and
        // those it warned about.
        fileExplorer.unsavedUnder = editorArea::dirtyUnder
        fileExplorer.onDeleted = editorArea::closeUnder
        // Git status markers, if git is installed and the folder is a repository (ADR 0013).
        fileExplorer.markers = { path, directory -> gitStatus.snapshot.get().markers(path, directory) }
        fileExplorer.onFilesChanged = gitStatus::refresh
        // The changes of modified files against HEAD are marked in their editors, updated with the status (ADR 0021).
        editorArea.diffOf = gitStatus::textDiff
        // Links in file views (e.g. the Markdown preview) to web pages open in the system browser.
        editorArea.openWebPage = { openWebPage(it) }
        // HTML previews are served with the project's files, and only those (ADR 0018).
        editorArea.projectFolder = { folder }
        editorArea.showError = { showError(stage, it) }
        // Views of .http files share the window's cookies, tokens and chosen environments (ADR 0026).
        editorArea.windowServices = listOf(httpSession)
        // It warns about private environment files that git would commit.
        httpSession.wouldCommit = { gitStatus.snapshot.get().wouldCommit(it) }
        gitStatus.snapshot.subscribe { snapshot ->
            stage.title = windowTitle(folder, snapshot.head)
            fileExplorer.refreshMarkers()
            editorArea.refreshDiffs()
            httpSession.gitStatusChanged()
            // The Commit tab lists what can be committed (ADR 0048).
            gitLogView.workingTreeChanged()
        }

        // The git log follows the repository's .git folder (commits, checkouts, fetches); collapsed, it only notes that
        // it is outdated (ADR 0035).
        gitStatus.gitFolderChanged.addListener { _, _, _ -> gitLogView.refresh() }
        gitLogView.expanded.addListener { _, _, expanded ->
            // Only a height it was laid out with while expanded (its content is already gone, so its minimum height is
            // the header's).
            if (!expanded && gitLogView.height > gitLogView.minHeight(-1.0) + 1) gitLogHeight = gitLogView.height
            fitGitLog(expanded)
        }
        // Collapsing the log with the focus in it gives the focus to the editor.
        gitLogView.returnFocus = { (editorArea.selectedTab?.content ?: editorArea).requestFocus() }
        // Files and what commits changed in them open in the editor area (ADR 0038).
        gitLogView.onOpenFile = { editorArea.open(it) }
        gitLogView.onShowDiff = ::showCommitDiff
        // How files differ from the working tree opens there too, with the file on disk on the right (ADR 0044).
        gitLogView.onShowWorkingTreeDiff = ::showWorkingTreeDiff
        // A checkout, cherry-pick or hard reset changes files on disk: edited files in the repository may be saved first
        // (ADRs 0041, 0042).
        gitLogView.beforeFileChange =
            { action -> gitStatus.root?.let { editorArea.saveBeforeGitChange(it, action) } ?: true }
        // Show History in the explorer and the editor's tabs opens a file's history in the git log area (ADR 0046).
        fileExplorer.showHistory = gitLogView::showHistory
        fileExplorer.canShowHistory = ::canShowHistory
        editorArea.showHistory = gitLogView::showHistory
        editorArea.canShowHistory = ::canShowHistory

        // A window gets its folder once (ADR 0014); other folders open in new windows.
        projectService.projectFolder.subscribe { folder ->
            fileExplorer.open(folder)
            gitStatus.open(folder)
            showGitLog(split, gitStatus.active)
            gitLogView.open(gitStatus.root)
            fitExplorer(split)
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

    /** Whether the git log area can show the history of [file]: while it is present, for a file below its repository. */
    private fun canShowHistory(file: Path): Boolean {
        val root = gitStatus.root ?: return false
        val normalized = file.toAbsolutePath().normalize()
        return gitLogPresent.get() && normalized != root && normalized.startsWith(root) &&
            !Files.isDirectory(normalized)
    }

    /**
     * Shows what a commit changed in a file side by side in a tab of its own, or selects the tab that already shows it
     * (ADR 0038); says so in the git log if the file is not shown as text.
     */
    private fun showCommitDiff(diff: CommitFileDiff) {
        editorArea.tabs
            .filterIsInstance<CommitDiffTab>()
            .firstOrNull { it.shows(diff) }
            ?.let { return editorArea.select(it) }
        val view = fileViewers.openDiff(nameOf(diff.path), diff.current, diff.toTextDiff())
        if (view == null) {
            gitLogView.showStatus("${diff.path.substringAfterLast('/')} cannot be shown as text.")
            return
        }
        editorArea.open(CommitDiffTab(diff, view))
    }

    /**
     * Shows how a file differs between a commit and the working tree side by side in a tab of its own, the file on disk
     * on the right where the JVM can read it (else the text read with the diff, also empty for a deleted file), and
     * focuses it; a tab that shows the same comparison already shows it read again instead (ADR 0044). `false` if the
     * file is not shown as text.
     */
    private fun showWorkingTreeDiff(diff: WorkingTreeFileDiff): Boolean {
        val file = liveFile(diff)
        val textDiff = diff.toTextDiff(unsaved = file != null && editorArea.dirtyUnder(file).isNotEmpty())
        val view =
            try {
                if (file != null) {
                    fileViewers.openDiff(file, textDiff)
                } else {
                    fileViewers.openDiff(nameOf(diff.path), diff.current, textDiff)
                }
            } catch (_: IOException) {
                null
            } ?: return false
        val tab = editorArea.tabs.filterIsInstance<WorkingTreeDiffTab>().firstOrNull { it.shows(diff) }
        if (tab != null) {
            tab.replace(diff, view)
            editorArea.select(tab)
        } else {
            editorArea.open(WorkingTreeDiffTab(diff, view))
        }
        view.node.requestFocus()
        return true
    }

    /** The file of [diff] on disk, if it is a regular file there that the JVM can name; not for a deleted one. */
    private fun liveFile(diff: WorkingTreeFileDiff): Path? {
        if (diff.change == GitChange.DELETED) return null
        val file =
            try {
                diff.root.resolve(diff.path)
            } catch (_: InvalidPathException) {
                return null
            }
        return file.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
    }

    /**
     * The repository path [path] as a [Path] that picks the file's viewer by its name; characters that file names
     * cannot have in the JVM's locale (non-ASCII ones in a plain ASCII locale) are replaced, keeping the extension.
     */
    private fun nameOf(path: String): Path =
        try {
            Path.of(path)
        } catch (_: InvalidPathException) {
            Path.of(path.substringAfterLast('/').map { if (it.code < 128) it else '_' }.joinToString(""))
        }

    /**
     * Shows the git log below the editor area if [present], else only the editor area, on the right of the explorer
     * [split]. Without the log there is neither its header nor a divider.
     */
    private fun showGitLog(
        split: SplitPane,
        present: Boolean,
    ) {
        gitLogPresent.set(present)
        val right = if (present) gitLogSplit else editorArea
        if (split.items.getOrNull(1) === right) return
        // Going from present to absent is defensive: a window gets its folder once today (ADR 0014).
        // The editor area moves between the two split panes; it can be in only one of them.
        if (split.items.size > 1) split.items.removeAt(1)
        if (present) gitLogSplit.items.setAll(editorArea, gitLogView) else gitLogSplit.items.clear()
        split.items.add(right)
        // The skin makes the new divider only in its next layout; fitExplorer measures it right after this.
        split.applyCss()
        split.layout()
        fitGitLog(gitLogView.expanded.get())
    }

    /**
     * Collapsed, the git log is pinned to the height of its header, so its divider cannot be dragged; expanded, it gets
     * its last height in pixels back, even if the window was resized meanwhile (at first the lower 30 %).
     */
    private fun fitGitLog(expanded: Boolean) {
        if (expanded) {
            gitLogView.maxHeight = Region.USE_COMPUTED_SIZE
            gitLogSplit.setDividerPositions(gitLogDividerPosition())
        } else {
            gitLogView.maxHeight = Region.USE_PREF_SIZE
            gitLogSplit.setDividerPositions(1.0)
        }
    }

    /**
     * The divider position that gives the git log its remembered height in the split's current height; the default
     * position while the height is unknown or the split is not laid out yet.
     */
    private fun gitLogDividerPosition(): Double {
        val total = gitLogSplit.height
        if (gitLogHeight <= 0 || total <= 0) return GIT_LOG_DIVIDER
        // The position is that of the divider's middle; a vertical split's divider has no preferred height, but the
        // split is laid out.
        val divider = gitLogSplit.lookupAll(".split-pane-divider").firstOrNull { it.parent === gitLogSplit }
        val half = (divider?.layoutBounds?.height ?: 0.0) / 2
        return (1 - (gitLogHeight + half) / total).coerceIn(0.0, 1.0)
    }

    /**
     * Gives the explorer its start width ([FileExplorerView.startWidth]), but at most half of a narrow window, at once
     * or, before the window is laid out, as soon as it is.
     */
    private fun fitExplorer(split: SplitPane) {
        // The position is that of the divider's middle. Views in the editor area may have split panes of their own.
        fun fit() {
            val divider = split.lookupAll(".split-pane-divider").firstOrNull { it.parent === split }
            val width = minOf(fileExplorer.startWidth(), split.width / 2)
            split.setDividerPositions((width + (divider?.prefWidth(-1.0) ?: 0.0) / 2) / split.width)
        }
        if (split.width > 0) {
            fit()
            return
        }
        split.widthProperty().addListener(
            object : InvalidationListener {
                override fun invalidated(observable: Observable) {
                    if (split.width <= 0) return
                    split.widthProperty().removeListener(this)
                    fit()
                }
            },
        )
    }

    private fun fileMenu(stage: Stage): Menu {
        val openFolder =
            MenuItem("Open Folder…").apply {
                accelerator = KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN)
                setOnAction { chooseFolder(stage) }
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
        val settings =
            MenuItem("Settings…").apply {
                accelerator = KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN)
                setOnAction { showSettings(stage) }
            }
        return Menu("File", null, openFolder, SeparatorMenuItem(), save, saveAll, SeparatorMenuItem(), settings)
    }

    private fun viewMenu(): Menu {
        // Switches between the editor of the selected file and its diff against HEAD (ADR 0021).
        val showChanges =
            MenuItem("Show Changes").apply {
                accelerator = KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
                disableProperty().bind(editorArea.canToggleDiff.map { !it })
                setOnAction { editorArea.toggleDiff() }
            }
        // IntelliJ's shortcut for its Git tool window.
        val gitLog =
            MenuItem("Git Log").apply {
                val modifier = if (isMac) KeyCombination.SHORTCUT_DOWN else KeyCombination.ALT_DOWN
                accelerator = KeyCodeCombination(KeyCode.DIGIT9, modifier)
                disableProperty().bind(gitLogPresent.not())
                setOnAction { gitLogView.expanded.set(!gitLogView.expanded.get()) }
            }
        return Menu("View", null, showChanges, gitLog)
    }

    private fun gitMenu(): Menu {
        // The Commit tab of the git log area, on IntelliJ's shortcut (ADR 0048).
        val commit =
            MenuItem("Commit…").apply {
                accelerator = KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN)
                disableProperty().bind(gitLogPresent.not())
                setOnAction { gitLogView.showCommit() }
            }
        return Menu("Git", null, commit)
    }

    private fun terminalMenu(stage: Stage): Menu {
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
     * Opens a terminal tab in the project folder, or in the home folder if no project is open; shows an error if the
     * shell cannot be started.
     */
    internal fun openTerminal(stage: Stage) {
        val folder = projectService.projectFolder.get() ?: Path.of(System.getProperty("user.home"))
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
        gitLogView.open(null)
    }
}
