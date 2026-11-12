package dev.sluide.ide.git

import dev.sluide.codereview.ReviewScope
import dev.sluide.codereview.Reviews
import dev.sluide.editorarea.EditorArea
import dev.sluide.gitlog.GitLogView
import dev.sluide.gitlog.REVIEW_BRANCH_KEYS
import dev.sluide.gitlog.ReviewRequest
import javafx.beans.value.ObservableValue
import javafx.scene.control.MenuItem
import javafx.stage.Stage
import java.nio.file.Path

/**
 * The review windows of one IDE window and what they ask of it (ADR 0168): the git log's review requests and Git →
 * Review Branch open them, their file links open in the editor area and their history links in the git log, they check
 * for changes when the repository does, and they close with the window. Used on the JavaFX thread.
 */
internal class WindowReviews(
    private val reviews: Reviews,
    private val gitLogView: GitLogView,
    private val editorArea: EditorArea,
    /** The repository open in the git log, `null` without one. */
    private val root: () -> Path?,
) {
    /** The review of the checked-out branch against its base, on Shortcut+Shift+R; [disabled] without a repository. */
    fun menuItem(disabled: ObservableValue<Boolean>): MenuItem =
        MenuItem("Review Branch").apply {
            accelerator = REVIEW_BRANCH_KEYS
            disableProperty().bind(disabled)
            setOnAction { open(ReviewScope.Branch("HEAD")) }
        }

    /** The IDE window, the owner of the review windows; set by [wire]. */
    private var stage: Stage? = null

    /** Connects the git log's requests and the review windows' links with the window; [window] is their owner. */
    fun wire(window: Stage) {
        stage = window
        gitLogView.onReview = { request ->
            open(
                when (request) {
                    is ReviewRequest.Branch -> ReviewScope.Branch(request.ref)
                    is ReviewRequest.Commits -> ReviewScope.Commits(request.hashes.last(), request.hashes.first())
                },
            )
        }
        // The line is 0-based, as the file views take it.
        reviews.openFile = { file, line ->
            if (editorArea.open(file) && line != null) editorArea.viewOf(file)?.showLine(line)
            toFront()
        }
        reviews.showHistory = { file ->
            gitLogView.showHistory(file)
            toFront()
        }
    }

    /** The review window is a window of its own: what a link shows is in the IDE window, so that comes to the front. */
    private fun toFront() {
        stage?.toFront()
        stage?.requestFocus()
    }

    private fun open(scope: ReviewScope) {
        val folder = root() ?: return
        stage?.let { reviews.open(folder, scope, it) }
    }

    /** The repository's `.git` folder or status changed: each review checks whether it is outdated. */
    fun repositoryChanged() = reviews.repositoryChanged()

    /**
     * Closes the review windows: with the IDE window, and when git's trust is withdrawn or the git log's repository
     * changes, because a review runs git (the staleness check) without a click while git is trusted.
     */
    fun closeAll() = reviews.closeAll()

    fun dispose() = closeAll()
}
