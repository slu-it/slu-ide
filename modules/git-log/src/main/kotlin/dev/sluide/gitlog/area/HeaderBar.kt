package dev.sluide.gitlog.area

import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.gitlog.REVIEW_BRANCH_KEYS
import dev.sluide.gitlog.push.PUSH_KEYS
import dev.sluide.gitlog.ui.contains
import javafx.beans.binding.Bindings
import javafx.beans.property.BooleanProperty
import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignA
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignF
import org.kordamp.ikonli.materialdesign2.MaterialDesignR
import org.kordamp.ikonli.materialdesign2.MaterialDesignS

/** The gap between the quick git actions (Review Branch, Rebase onto origin's default) and Push. */
private const val QUICK_ACTIONS_GAP = 12.0

/**
 * Whether the header may rebase onto `origin`'s default branch: the last read found a remote named `origin`, `HEAD` is
 * on a branch and there is a commit (a `HEAD` ref).
 */
internal fun rebaseOntoOriginPossible(
    remotes: List<String>?,
    refs: GitRefs?,
): Boolean =
    remotes.orEmpty().contains("origin") &&
        refs?.currentBranch != null &&
        refs.refs.any { it.kind == GitRefKind.HEAD }

/**
 * The header bar of the git log area (ADRs 0035, 0046): a chevron that follows [expanded], the tabs' [titles], the
 * [status] message, Cancel (only while the operation going on is [cancellable], ADR 0043) and, on the right, Review
 * Branch (ADR 0168; off with [noRepository], it calls [review]) and Fetch origin and Rebase onto its Default Branch
 * (ADR 0063; off with [noRebaseOntoDefault], it calls [rebaseOntoDefault]), the quick git actions, then Push, Pull,
 * Fetch All Remotes and Refresh. Fetch and Pull are off while an operation is [running] or there are [noRemotes],
 * Push without a remote. Clicking the bar (but not its buttons) expands or collapses the area.
 */
internal class HeaderBar(
    titles: Node,
    status: Node,
    expanded: BooleanProperty,
    running: ObservableBooleanValue,
    cancellable: ObservableBooleanValue,
    noRemotes: ObservableBooleanValue,
    noRepository: ObservableBooleanValue,
    review: () -> Unit,
    rebaseOntoDefault: () -> Unit,
    noRebaseOntoDefault: ObservableBooleanValue,
    refresh: () -> Unit,
    fetch: () -> Unit,
    push: () -> Unit,
    pull: () -> Unit,
    cancel: () -> Unit,
) {
    private val chevron = FontIcon(MaterialDesignC.CHEVRON_RIGHT)

    val refreshButton =
        Button(null, FontIcon(MaterialDesignR.REFRESH)).apply {
            styleClass += "git-log-refresh"
            tooltip = Tooltip("Refresh")
            isFocusTraversable = false
            // Always whole: the tabs left of it give way first.
            minWidth = Region.USE_PREF_SIZE
            setOnAction { refresh() }
        }

    /** Fetches all remotes (ADR 0043); not while an operation runs or there is no remote. */
    val fetchButton =
        Button(null, FontIcon(MaterialDesignA.ARROW_DOWN)).apply {
            styleClass += "git-log-fetch"
            tooltip = Tooltip("Fetch All Remotes")
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            setOnAction { fetch() }
            disableProperty().bind(Bindings.or(running, noRemotes))
        }

    /** Reviews `HEAD`'s branch like a pull request (ADR 0168); only while the log shows a repository. */
    val reviewButton =
        Button(null, FontIcon(MaterialDesignF.FILE_EYE_OUTLINE)).apply {
            styleClass += "git-log-review"
            tooltip = Tooltip("Review Branch (${REVIEW_BRANCH_KEYS.displayText})")
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            setOnAction { review() }
            disableProperty().bind(noRepository)
        }

    /** Fetches `origin` and rebases the current branch onto its default branch (ADR 0063). */
    val rebaseDefaultButton =
        Button(null, FontIcon(MaterialDesignS.SOURCE_BRANCH_SYNC)).apply {
            styleClass += "git-log-rebase-default"
            tooltip = Tooltip("Fetch origin and Rebase onto its Default Branch")
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            setOnAction { rebaseOntoDefault() }
            disableProperty().bind(noRebaseOntoDefault)
        }

    /** Opens the Push dialog (ADR 0049); not without a remote, but while an operation runs. */
    val pushButton =
        Button(null, FontIcon(MaterialDesignA.ARROW_UP)).apply {
            styleClass += "git-log-push"
            tooltip = Tooltip("Push… (${PUSH_KEYS.displayText})")
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            setOnAction { push() }
            disableProperty().bind(noRemotes)
        }

    /** Opens the Pull dialog (ADR 0106); not while an operation runs or without a remote. */
    val pullButton =
        Button(null, FontIcon(MaterialDesignS.SOURCE_PULL)).apply {
            styleClass += "git-log-pull"
            tooltip = Tooltip("Pull…")
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            setOnAction { pull() }
            disableProperty().bind(Bindings.or(running, noRemotes))
        }

    /** Cancels the fetch, commit or push going on; shown only while there is one. */
    val cancelButton =
        Button("Cancel").apply {
            styleClass += "git-log-cancel"
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            visibleProperty().bind(cancellable)
            managedProperty().bind(cancellable)
            setOnAction { cancel() }
        }

    val node: HBox =
        HBox(
            chevron,
            titles,
            status,
            cancelButton,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            reviewButton,
            rebaseDefaultButton,
            pushButton,
            pullButton,
            fetchButton,
            refreshButton,
        ).apply {
            styleClass += "git-log-header"
            alignment = Pos.CENTER_LEFT
            setOnMouseClicked { event ->
                val onButton =
                    listOf(
                        refreshButton,
                        reviewButton,
                        rebaseDefaultButton,
                        pushButton,
                        pullButton,
                        fetchButton,
                        cancelButton,
                    ).any { it.contains(event.target) }
                if (event.button == MouseButton.PRIMARY && !onButton) {
                    expanded.set(!expanded.get())
                }
            }
        }

    init {
        // The spacing comes from the stylesheet: the margin makes up the rest of the wider gap after the quick actions.
        node.spacingProperty().subscribe { spacing ->
            HBox.setMargin(
                rebaseDefaultButton,
                Insets(0.0, maxOf(0.0, QUICK_ACTIONS_GAP - spacing.toDouble()), 0.0, 0.0),
            )
        }
        expanded.subscribe { isExpanded ->
            chevron.iconCode = if (isExpanded) MaterialDesignC.CHEVRON_DOWN else MaterialDesignC.CHEVRON_RIGHT
        }
    }
}
