package dev.sluide.gitlog.area

import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.gitlog.REVIEW_BRANCH_KEYS
import dev.sluide.gitlog.push.PUSH_KEYS
import dev.sluide.gitlog.ui.GitLogIcons
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
import javafx.util.Duration
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignR

/** How long the pointer rests on a header action before its tooltip shows; shorter than JavaFX's 1 s default. */
private const val TOOLTIP_DELAY_MILLIS = 250.0

/** A tooltip for a header action, shown after [TOOLTIP_DELAY_MILLIS]. */
private fun actionTooltip(text: String) = Tooltip(text).apply { showDelay = Duration.millis(TOOLTIP_DELAY_MILLIS) }

/**
 * Whether the header may rebase onto `origin`'s default branch: a remote named `origin` ([hasOrigin]), `HEAD` on a
 * branch ([onBranch], not detached) and a commit ([hasCommit]).
 */
internal fun rebaseOntoOriginPossible(
    hasOrigin: Boolean,
    onBranch: Boolean,
    hasCommit: Boolean,
): Boolean = hasOrigin && onBranch && hasCommit

/** [rebaseOntoOriginPossible] for the [remotes] and [refs] the log's pages read; `null` for what could not be read. */
internal fun rebaseOntoOriginPossible(
    remotes: List<String>?,
    refs: GitRefs?,
): Boolean =
    rebaseOntoOriginPossible(
        hasOrigin = remotes.orEmpty().contains("origin"),
        onBranch = refs?.currentBranch != null,
        hasCommit = refs?.refs.orEmpty().any { it.kind == GitRefKind.HEAD },
    )

/**
 * The header bar of the git log area (ADRs 0035, 0046): a chevron that follows [expanded], the tabs' [titles], the
 * [status] message, Cancel (only while the operation going on is [cancellable], ADR 0043) and, on the right, Review
 * Branch (ADR 0168; off with [noRepository], it calls [review]) and Fetch origin and Rebase onto its Default Branch
 * (ADR 0063; off with [noRebaseOntoDefault], it calls [rebaseOntoDefault]) and Fetch origin and Create new Branch
 * from its Default Branch (ADR 0181; off with [noNewBranchFromDefault], it calls [newBranchFromDefault]), then Push,
 * Pull, Fetch All Remotes and Refresh: three groups one button width apart, all outlined alike, with tooltips after
 * 250 ms. Fetch and Pull are off while an operation is [running] or there are [noRemotes], Push without a remote.
 * Clicking the bar (but not its buttons) expands or collapses the area.
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
    newBranchFromDefault: () -> Unit,
    noNewBranchFromDefault: ObservableBooleanValue,
    refresh: () -> Unit,
    fetch: () -> Unit,
    push: () -> Unit,
    pull: () -> Unit,
    cancel: () -> Unit,
) {
    private val chevron = FontIcon(MaterialDesignC.CHEVRON_RIGHT)

    val refreshButton =
        Button(null, FontIcon(MaterialDesignR.REFRESH)).apply {
            styleClass += listOf("git-log-action", "git-log-refresh")
            tooltip = actionTooltip("Refresh")
            isFocusTraversable = false
            // Always whole: the tabs left of it give way first.
            minWidth = Region.USE_PREF_SIZE
            setOnAction { refresh() }
        }

    /** Fetches all remotes (ADR 0043); not while an operation runs or there is no remote. */
    val fetchButton =
        Button(null, FontIcon(GitLogIcons.fetch)).apply {
            styleClass += listOf("git-log-action", "git-log-fetch")
            tooltip = actionTooltip("Fetch All Remotes")
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            setOnAction { fetch() }
            disableProperty().bind(Bindings.or(running, noRemotes))
        }

    /** Reviews `HEAD`'s branch like a pull request (ADR 0168); only while the log shows a repository. */
    val reviewButton =
        Button(null, FontIcon(GitLogIcons.review)).apply {
            styleClass += listOf("git-log-action", "git-log-review")
            tooltip = actionTooltip("Review Branch (${REVIEW_BRANCH_KEYS.displayText})")
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            setOnAction { review() }
            disableProperty().bind(noRepository)
        }

    /** Fetches `origin` and rebases the current branch onto its default branch (ADR 0063). */
    val rebaseDefaultButton =
        Button(null, FontIcon(GitLogIcons.rebase)).apply {
            styleClass += listOf("git-log-action", "git-log-rebase-default")
            tooltip = actionTooltip("Fetch origin and Rebase onto its Default Branch")
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            setOnAction { rebaseOntoDefault() }
            disableProperty().bind(noRebaseOntoDefault)
        }

    /** Fetches `origin` and asks for a new branch at its default branch (ADR 0181). */
    val newBranchDefaultButton =
        Button(null, FontIcon(GitLogIcons.newBranch)).apply {
            styleClass += listOf("git-log-action", "git-log-new-branch-default")
            tooltip = actionTooltip("Fetch origin and Create new Branch from its Default Branch")
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            setOnAction { newBranchFromDefault() }
            disableProperty().bind(noNewBranchFromDefault)
        }

    /** Opens the Push dialog (ADR 0049); not without a remote, but while an operation runs. */
    val pushButton =
        Button(null, FontIcon(GitLogIcons.push)).apply {
            styleClass += listOf("git-log-action", "git-log-push")
            tooltip = actionTooltip("Push… (${PUSH_KEYS.displayText})")
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            setOnAction { push() }
            disableProperty().bind(noRemotes)
        }

    /** Opens the Pull dialog (ADR 0106); not while an operation runs or without a remote. */
    val pullButton =
        Button(null, FontIcon(GitLogIcons.pull)).apply {
            styleClass += listOf("git-log-action", "git-log-pull")
            tooltip = actionTooltip("Pull…")
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
        object : HBox(
            chevron,
            titles,
            status,
            cancelButton,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            reviewButton,
            rebaseDefaultButton,
            newBranchDefaultButton,
            pushButton,
            pullButton,
            fetchButton,
            refreshButton,
        ) {
            /**
             * Three groups, one button width apart: tooling for the current state (Review), moving or starting a local
             * branch (Rebase, New Branch), the remote round trip (Push, Pull, Fetch, Refresh). The margin after a
             * group's last button makes up the rest of the gap; it is set before each layout from Refresh's preferred
             * width (its icon and the stylesheet's padding, known after the CSS pass) and the stylesheet's spacing, so
             * it is right from the first layout on and follows changes without listeners.
             */
            override fun layoutChildren() {
                val margin = maxOf(0.0, refreshButton.prefWidth(-1.0) - spacing)
                listOf(reviewButton, newBranchDefaultButton).forEach {
                    if (getMargin(it)?.right != margin) setMargin(it, Insets(0.0, margin, 0.0, 0.0))
                }
                super.layoutChildren()
            }
        }.apply {
            styleClass += "git-log-header"
            alignment = Pos.CENTER_LEFT
            setOnMouseClicked { event ->
                val onButton =
                    listOf(
                        refreshButton,
                        reviewButton,
                        rebaseDefaultButton,
                        newBranchDefaultButton,
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
        expanded.subscribe { isExpanded ->
            chevron.iconCode = if (isExpanded) MaterialDesignC.CHEVRON_DOWN else MaterialDesignC.CHEVRON_RIGHT
        }
    }
}
