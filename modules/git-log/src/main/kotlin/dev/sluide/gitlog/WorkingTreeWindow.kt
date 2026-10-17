package dev.sluide.gitlog

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitFileDiff
import javafx.geometry.Pos
import javafx.geometry.Rectangle2D
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.Window
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignR
import java.nio.file.Files
import java.nio.file.Path

/**
 * A top-level window of its own, not owned by the IDE window [ideWindow] (so that the IDE window can come in front of
 * it) and not modal, with the files that differ between a commit and the working
 * tree (ADR 0044), as [comparison] reads them: a line with their number, a Refresh button, the tree of the changed
 * files ([ChangeTreeView]) and a note that untracked files are not included. It reads them again when it gains the
 * focus, as changes in the working tree are not watched. Show Diff hands a file's diff to [showDiff], which tells
 * whether it could be shown as text; Open Local File (F4) hands the file to [openFile]. Themed like every window
 * (`ThemedWindows`, ADR 0009), with `git-log.css` and the `.git-log` colours, and the IDE window's icons. Shown first at
 * the IDE window's right edge, within its screen. Whoever opens it closes it with the IDE window.
 */
internal class WorkingTreeWindow(
    private val ideWindow: Window?,
    val comparison: WorkingTreeComparison,
    private val showDiff: (WorkingTreeFileDiff) -> Boolean,
    private val openFile: (Path) -> Unit,
    private val localFile: (GitChangedFile) -> Path?,
) {
    private val target = comparison.target

    internal val header = Label().apply { styleClass += "git-working-tree-header" }

    /** Why a diff cannot be shown, until the next selection or refresh. */
    internal val status =
        Label().apply {
            styleClass += "git-working-tree-status"
            minWidth = 0.0
        }

    internal val refreshButton =
        Button(null, FontIcon(MaterialDesignR.REFRESH)).apply {
            styleClass += "git-working-tree-refresh"
            tooltip = Tooltip("Refresh")
            setOnAction { refresh() }
        }

    internal val placeholder = Label().apply { styleClass += "git-details-placeholder" }

    internal val tree =
        ChangeTreeView().apply {
            showDiff = ::showFileDiff
            openFile = { file -> localFile(file)?.let(this@WorkingTreeWindow.openFile) }
            exists = { file -> localFile(file)?.let(Files::isRegularFile) == true }
        }

    internal val note = Label("Untracked files are not included.").apply { styleClass += "git-working-tree-note" }

    internal val stage =
        Stage().apply {
            (ideWindow as? Stage)?.let { icons.setAll(it.icons) }
            title = "${target.label} ↔ Working Tree"
            scene =
                Scene(
                    BorderPane().apply {
                        // .git-log gives the looked-up colours of the changes: the window is one of its own.
                        styleClass.addAll("git-log", "git-working-tree")
                        stylesheets += GIT_LOG_CSS
                        top =
                            HBox(
                                header,
                                status,
                                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                                refreshButton,
                            ).apply {
                                styleClass += "git-working-tree-bar"
                                alignment = Pos.CENTER_LEFT
                            }
                        center = StackPane(tree, placeholder)
                        bottom = note
                    },
                    WIDTH,
                    HEIGHT,
                )
            // Edits in the working tree are not watched: read again when the user comes back.
            focusedProperty().subscribe { _, focused -> if (focused && !comparison.loading) refresh() }
            setOnHidden { comparison.close() }
        }

    /** Whether files were shown since the window opened, so that a refresh keeps the selection. */
    private var filesShown = false

    init {
        comparison.state.subscribe { state -> showState(state) }
        tree.selectionModel.selectedItemProperty().subscribe { _ -> status.text = "" }
    }

    /** Reads the files again and shows them, keeping the selected file and collapsed folders. */
    fun refresh() {
        status.text = ""
        comparison.refresh()
    }

    /** Shows the window and reads the files, or brings it to the front if it is shown already. */
    fun show() {
        if (stage.isShowing) {
            stage.toFront()
            stage.requestFocus()
            return
        }
        comparison.refresh()
        ideWindow?.let(::placeBeside)
        stage.show()
    }

    /** Puts the window at the right edge of [ide], vertically centred on it, within the screen [ide] is on. */
    private fun placeBeside(ide: Window) {
        val ideBounds = Rectangle2D(ide.x, ide.y, ide.width.coerceAtLeast(1.0), ide.height.coerceAtLeast(1.0))
        val area = (Screen.getScreensForRectangle(ideBounds).firstOrNull() ?: Screen.getPrimary()).visualBounds
        val width = minOf(WIDTH, area.width)
        val height = minOf(HEIGHT, area.height)
        stage.width = width
        stage.height = height
        stage.x = (ide.x + ide.width - width).coerceIn(area.minX, area.maxX - width)
        stage.y = (ide.y + (ide.height - height) / 2).coerceIn(area.minY, area.maxY - height)
    }

    fun close() = stage.close()

    private fun showState(state: WorkingTreeState) {
        when (state) {
            WorkingTreeState.Loading -> {
                // A refresh keeps the files shown until the new ones are there.
                if (!filesShown) showPlaceholder("Loading…")
            }

            is WorkingTreeState.Loaded -> {
                showFiles(state.files)
            }

            WorkingTreeState.Missing -> {
                showPlaceholder("${target.label} no longer exists.")
            }

            WorkingTreeState.Failed -> {
                showPlaceholder("The differences could not be read.")
            }
        }
    }

    private fun showPlaceholder(text: String) {
        filesShown = false
        tree.clear()
        tree.isVisible = false
        placeholder.text = text
        placeholder.isVisible = true
        header.text = ""
    }

    private fun showFiles(files: List<GitChangedFile>) {
        if (files.isEmpty()) {
            showPlaceholder("No differences.")
            return
        }
        header.text = if (files.size == 1) "1 file differs" else "${files.size} files differ"
        tree.show(files, keep = filesShown)
        filesShown = true
        placeholder.isVisible = false
        tree.isVisible = true
    }

    /** Reads the diff of [file] and hands it to [showDiff]; what keeps it from being shown goes to the status. */
    private fun showFileDiff(file: GitChangedFile) {
        status.text = ""
        val name = file.path.substringAfterLast('/')
        comparison.diff(file) { commit, diff ->
            val message =
                when {
                    diff == null -> {
                        "The diff could not be read."
                    }

                    diff == GitFileDiff.Binary -> {
                        "$name is a binary file."
                    }

                    diff == GitFileDiff.TooLarge -> {
                        "$name is too large to compare."
                    }

                    diff is GitFileDiff.Submodule -> {
                        "$name is a submodule."
                    }

                    diff is GitFileDiff.Text && diff.original == diff.current && file.change.changesContent -> {
                        "$name has no content changes."
                    }

                    diff is GitFileDiff.Text -> {
                        val shown =
                            showDiff(
                                WorkingTreeFileDiff(
                                    target.root,
                                    file.path,
                                    file.oldPath,
                                    file.change,
                                    commit,
                                    target.label,
                                    diff.original,
                                    diff.current,
                                    diff.hunks,
                                ),
                            )
                        if (shown) null else "$name cannot be shown as text."
                    }

                    else -> {
                        null
                    }
                }
            if (message != null) status.text = message
        }
    }

    private companion object {
        const val WIDTH = 520.0
        const val HEIGHT = 480.0
    }
}

/** Whether a file with this change had content on both sides, which may not have changed. */
internal val GitChange.changesContent get() = this != GitChange.ADDED && this != GitChange.DELETED
