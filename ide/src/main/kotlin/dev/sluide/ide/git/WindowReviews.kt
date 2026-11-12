package dev.sluide.ide.git

import dev.sluide.codereview.ReviewScope
import dev.sluide.codereview.Reviews
import dev.sluide.editorarea.EditorArea
import dev.sluide.gitlog.GitLogView
import dev.sluide.gitlog.REVIEW_BRANCH_KEYS
import dev.sluide.gitlog.ReviewRequest
import javafx.beans.value.ObservableValue
import javafx.scene.control.MenuItem
import javafx.stage.Window
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
    /** The review of the checked-out branch against its base, on Shortcut+Shift+R; needs a repository. */
    fun menuItem(hasRepository: ObservableValue<Boolean>): MenuItem =
        MenuItem("Review Branch").apply {
            accelerator = REVIEW_BRANCH_KEYS
            disableProperty().bind(hasRepository.map { !it })
            setOnAction { open(ReviewScope.Branch("HEAD")) }
        }

    /** The IDE window, the owner of the review windows; set by [wire]. */
    private var stage: Window? = null

    /** Connects the git log's requests and the review windows' links with the window; [window] is their owner. */
    fun wire(window: Window) {
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
        }
        reviews.showHistory = gitLogView::showHistory
    }

    private fun open(scope: ReviewScope) {
        val folder = root() ?: return
        stage?.let { reviews.open(folder, scope, it) }
    }

    /** The repository's `.git` folder or status changed: each review checks whether it is outdated. */
    fun repositoryChanged() = reviews.repositoryChanged()

    /** Closes the review windows with the IDE window. */
    fun dispose() = reviews.closeAll()
}
