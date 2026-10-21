package dev.sluide.ide.git

import dev.sluide.editor.EditorArea
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileviewers.FileViewers
import dev.sluide.git.GitStatusService
import dev.sluide.git.GitTrustCheck
import dev.sluide.gitlog.GitLogView
import dev.sluide.projecttrust.ProjectTrust
import dev.sluide.projecttrust.ProjectTrustState
import javafx.beans.property.SimpleBooleanProperty
import javafx.geometry.Orientation
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.control.SplitPane
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.layout.Region
import java.nio.file.Files
import java.nio.file.Path

/** Alt+digit types characters on macOS, so window shortcuts with digits use Cmd there. */
private val isMac = System.getProperty("os.name").lowercase().contains("mac")

/** Where the divider above the git log starts when it is expanded: the log takes the lower 30 % (ADR 0035). */
private const val GIT_LOG_DIVIDER = 0.7

/** What the git log area says while the folder's trust keeps git off (ADR 0053). */
internal const val GIT_OFF = "Git is off until the folder is trusted."

/**
 * The git log below the editor area of one window and its integration: its place in the window, the Git menu and the
 * View menu's Git Log item, and the wiring with the explorer, the editor area and the git status (ADRs 0035, 0053).
 * Used on the JavaFX thread.
 */
internal class WindowGitLog(
    private val gitLogView: GitLogView,
    private val gitStatus: GitStatusService,
    private val projectTrust: ProjectTrust,
    private val editorArea: EditorArea,
    private val fileExplorer: FileExplorerView,
    fileViewers: FileViewers,
) {
    private val diffTabs = GitDiffTabs(editorArea, fileViewers, gitLogView::showStatus)

    /** Whether the git log is shown below the editor area: only for folders whose git status is followed. */
    private val gitLogPresent = SimpleBooleanProperty(false)

    /** The editor area above the git log, while it is present (ADR 0035). */
    private val gitLogSplit =
        SplitPane().apply {
            orientation = Orientation.VERTICAL
            // Resizing the window resizes the editor area; the log keeps its height.
            SplitPane.setResizableWithParent(gitLogView, false)
        }

    /** The repository open in the git log: the followed one while the folder's trust allows git, else `null`. */
    private var gitLogRoot: Path? = null

    /** Whether the git log has the repository open; Commit… and Push… need it. */
    private val gitLogOpen = SimpleBooleanProperty(false)

    /** The height of the expanded git log in pixels, kept while it is collapsed; 0 until it was expanded. */
    private var gitLogHeight = 0.0

    /** Connects the git log with the git status, the editor area and the explorer. */
    fun wire() {
        // The git log follows the repository's .git folder (commits, checkouts, fetches); collapsed, it only notes that
        // it is outdated (ADR 0035).
        gitStatus.gitFolderChanged.addListener { _, _, _ -> gitLogView.refresh() }
        // Its own operations hold the status while they run and count as one change at their end, once git's trust
        // check ran again on what they may have changed (ADRs 0053, 0062): the log reloads once per operation.
        gitLogView.onWriteStarted = gitStatus::writeStarted
        gitLogView.onWriteFinished = gitStatus::writeFinished
        gitLogView.expanded.addListener { _, _, expanded ->
            // Only a height it was laid out with while expanded (its content is already gone, so its minimum height is
            // the header's).
            if (!expanded && gitLogView.height > gitLogView.minHeight(-1.0) + 1) gitLogHeight = gitLogView.height
            fitGitLog(expanded)
        }
        // Collapsing the log with the focus in it gives the focus to the editor.
        gitLogView.returnFocus = editorArea::focusSelected
        // Files and what commits changed in them open in the editor area (ADR 0038).
        gitLogView.onOpenFile = { editorArea.open(it) }
        gitLogView.onShowDiff = diffTabs::showCommitDiff
        // How files differ from the working tree opens there too, with the file on disk on the right (ADR 0044).
        gitLogView.onShowWorkingTreeDiff = diffTabs::showWorkingTreeDiff
        // A checkout, cherry-pick or hard reset changes files on disk: edited files in the repository may be saved first
        // (ADRs 0041, 0042).
        gitLogView.beforeFileChange =
            { action -> gitStatus.root?.let { editorArea.saveBeforeGitChange(it, action) } ?: true }
        // Show History in the explorer and the editor's tabs opens a file's history in the git log area (ADR 0046).
        fileExplorer.showHistory = gitLogView::showHistory
        fileExplorer.canShowHistory = ::canShowHistory
        editorArea.showHistory = gitLogView::showHistory
        editorArea.canShowHistory = ::canShowHistory
    }

    /** The Commit tab lists what can be committed: tells it that the working tree changed (ADR 0048). */
    fun workingTreeChanged() = gitLogView.workingTreeChanged()

    /**
     * Whether the git log area can show the history of [file]: while it is present and has the repository open (the
     * folder's trust allows git), for a file below it.
     */
    private fun canShowHistory(file: Path): Boolean {
        val root = gitLogRoot ?: return false
        val normalized = file.toAbsolutePath().normalize()
        return gitLogPresent.get() && normalized != root && normalized.startsWith(root) &&
            !Files.isDirectory(normalized)
    }

    /**
     * Opens the repository in the git log while the folder's trust allows git, and reads its status again when it
     * starts to; closes it in the log otherwise (ADR 0053). The status follows the trust by itself.
     */
    fun followTrust() {
        val root = gitStatus.root?.takeIf { projectTrust.allows(GitTrustCheck.ID) }
        if (root != gitLogRoot) {
            gitLogRoot = root
            gitLogOpen.set(root != null)
            // Like another folder: the Commit tab's draft and the file histories are closed, a fetch is stopped.
            gitLogView.open(root)
            if (root != null) {
                gitLogView.showStatus("")
                gitStatus.refresh()
            }
        }
        // Not while the checks run: they take a moment.
        val off = projectTrust.state.get().let { it is ProjectTrustState.Ask || it is ProjectTrustState.Untrusted }
        if (root == null && gitStatus.active && off) gitLogView.showStatus(GIT_OFF)
    }

    /**
     * Shows the git log below the editor area if [present], else only the editor area, on the right of the explorer
     * [split]. Without the log there is neither its header nor a divider.
     */
    fun show(
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

    /** The View menu's item that expands and collapses the git log; IntelliJ's shortcut for its Git tool window. */
    fun gitLogMenuItem(): MenuItem =
        MenuItem("Git Log").apply {
            val modifier = if (isMac) KeyCombination.SHORTCUT_DOWN else KeyCombination.ALT_DOWN
            accelerator = KeyCodeCombination(KeyCode.DIGIT9, modifier)
            disableProperty().bind(gitLogPresent.not())
            setOnAction { gitLogView.expanded.set(!gitLogView.expanded.get()) }
        }

    /** The Git menu: Commit… and Push…, disabled while the git log has no repository open. */
    fun gitMenu(): Menu {
        // The Commit tab of the git log area, on IntelliJ's shortcut (ADR 0048).
        val commit =
            MenuItem("Commit…").apply {
                accelerator = KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN)
                disableProperty().bind(gitLogOpen.not())
                setOnAction { gitLogView.showCommit() }
            }
        // The Push dialog (ADR 0049), on IntelliJ's shortcut; delete lines moved to Shortcut+Y for it.
        val push =
            MenuItem("Push…").apply {
                accelerator = KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
                disableProperty().bind(gitLogOpen.not())
                setOnAction { gitLogView.showPush() }
            }
        return Menu("Git", null, commit, push)
    }

    /** Closes the repository in the git log; called when the window is closed. */
    fun dispose() {
        gitLogRoot = null
        gitLogOpen.set(false)
        gitLogView.open(null)
    }
}
