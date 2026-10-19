package dev.sluide.gitlog.operations

import dev.sluide.git.GitInProgress
import dev.sluide.git.GitPendingOperation
import dev.sluide.git.GitPendingPick
import dev.sluide.git.GitPendingRebase
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.stage.Window

/**
 * The bar of what git is in the middle of (ADR 0042), below the filter bar: a cherry-pick or revert to continue or
 * abort, or a merge, rebase or sequence left to the terminal; hidden while there is nothing. The buttons are disabled
 * while a write [running]; they hand their operations to [start], Abort after the confirmation, which is shown on the
 * [window].
 */
internal class PendingBar(
    private val running: ObservableBooleanValue,
    private val window: () -> Window?,
) : HBox() {
    /** Starts the operation (Continue, or Skip or Abort once confirmed); set by the view. */
    var start: (RefOperation) -> Unit = {}

    private val busyState = SimpleBooleanProperty(false)

    /** Whether git is in the middle of something, so that cherry-pick and revert are not offered. */
    val busy: ObservableBooleanValue = busyState

    /** The single cherry-pick or revert, or the rebase, in progress the bar continues or aborts; `null` for others. */
    private var pending: GitInProgress? = null

    /** Shrinks with the log, as the buttons do not; its tooltip has the whole text. */
    internal val label =
        Label().apply {
            styleClass += "git-log-pending-text"
            minWidth = 0.0
            tooltip = Tooltip()
        }

    internal val continueButton =
        Button("Continue").apply {
            styleClass += "git-log-pending-continue"
            minWidth = USE_PREF_SIZE
            setOnAction {
                when (val p = pending) {
                    is GitPendingPick -> {
                        start(ContinuePick(p.kind, p.commit))
                    }

                    is GitPendingRebase -> {
                        start(ContinueRebase())
                    }

                    else -> {}
                }
            }
            disableProperty().bind(running)
        }

    /** Skips the commit a rebase stopped at; for a rebase only. */
    internal val skipButton =
        Button("Skip").apply {
            styleClass += "git-log-pending-skip"
            minWidth = USE_PREF_SIZE
            setOnAction { (pending as? GitPendingRebase)?.let(::skipPending) }
            disableProperty().bind(running)
        }

    internal val abortButton =
        Button("Abort").apply {
            styleClass += "git-log-pending-abort"
            minWidth = USE_PREF_SIZE
            setOnAction { pending?.let(::abortPending) }
            disableProperty().bind(running)
        }

    init {
        children.addAll(
            label,
            Region().apply { setHgrow(this, Priority.ALWAYS) },
            continueButton,
            skipButton,
            abortButton,
        )
        styleClass += "git-log-pending"
        alignment = Pos.CENTER_LEFT
        isVisible = false
        isManaged = false
    }

    /**
     * Shows what git is in the middle of ([inProgress]), with Continue and Abort for a single cherry-pick or revert or
     * a rebase (which also has Skip) only; hides the bar for `null`.
     */
    fun show(inProgress: GitInProgress?) {
        pending = inProgress.takeIf { it is GitPendingPick || it is GitPendingRebase }
        busyState.set(inProgress != null)
        label.text =
            when (inProgress) {
                is GitPendingPick -> pendingText(inProgress)
                is GitPendingOperation -> operationText(inProgress.operation)
                is GitPendingRebase -> pendingRebaseText(inProgress)
                null -> ""
            }
        label.tooltip.text = label.text
        isVisible = inProgress != null
        isManaged = inProgress != null
        listOf(continueButton, abortButton).forEach {
            it.isVisible = pending != null
            it.isManaged = pending != null
        }
        skipButton.isVisible = pending is GitPendingRebase
        skipButton.isManaged = pending is GitPendingRebase
    }

    /** Skips the commit the [pending] rebase stopped at once confirmed; Cancel is the default. */
    private fun skipPending(pending: GitPendingRebase) {
        if (running.value) return
        val confirmation = RefConfirmation("Skip Commit", skipRebaseQuestion(pending), SKIP_REBASE_BUTTON, SkipRebase)
        if (refConfirmation(window(), confirmation).showAndWait().orElse(ButtonType.CANCEL) == confirmation.button) {
            start(confirmation.confirmed)
        }
    }

    /**
     * Aborts the cherry-pick, revert or rebase in progress ([pending]) once confirmed, as it undoes what was done to
     * resolve its conflicts; Cancel is the default.
     */
    private fun abortPending(pending: GitInProgress) {
        if (running.value) return
        val confirmation =
            when (pending) {
                is GitPendingPick -> {
                    RefConfirmation(
                        "Abort ${pending.kind.title}",
                        abortQuestion(pending.kind, pending.commit),
                        abortButton(pending.kind),
                        AbortPick(pending.kind, pending.commit),
                    )
                }

                is GitPendingRebase -> {
                    RefConfirmation(
                        "Abort Rebase",
                        abortRebaseQuestion(pending.branch),
                        ABORT_REBASE_BUTTON,
                        AbortRebase,
                    )
                }

                else -> {
                    return
                }
            }
        if (refConfirmation(window(), confirmation).showAndWait().orElse(ButtonType.CANCEL) == confirmation.button) {
            start(confirmation.confirmed)
        }
    }
}
