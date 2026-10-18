package dev.sluide.gitlog.table

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.gitlog.ui.GIT_LOG_CSS
import dev.sluide.gitlog.ui.refIcon
import dev.sluide.gitlog.ui.refLines
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.shape.Rectangle

/**
 * The ref labels of a git log row (ADR 0037): one coloured tag icon per kind of ref the row has, slightly overlapping,
 * then the text of the first group and `+N` for the others ([refGroups]). Narrower than wanted, the text is ellipsized.
 * Hovering shows every ref in a tooltip, built only when it is shown.
 */
internal class RefLabels : HBox() {
    private val icons = GitRefKind.entries.associateWith(::refIcon)

    internal val text = Label().apply { styleClass += "git-ref-text" }

    /** The refs shown, `HEAD` first. */
    var refs: List<GitRef> = emptyList()
        private set

    internal val tooltip =
        Tooltip().apply {
            styleClass += "git-ref-tooltip"
            // Its content is not in the view's scene, so it gets the stylesheet (and the colours) of its own.
            scene.stylesheets += GIT_LOG_CSS
            setOnShowing { graphic = VBox(*refLines(refs).toTypedArray()).apply { styleClass += "git-ref-lines" } }
        }

    init {
        styleClass += "git-ref-labels"
        alignment = Pos.CENTER_LEFT
        val stack =
            HBox().apply {
                styleClass += "git-ref-icons"
                alignment = Pos.CENTER_LEFT
                children.addAll(icons.values)
            }
        // The first kind on top, the others peeking out from under it.
        icons.values.forEachIndexed { index, icon -> icon.viewOrder = index.toDouble() }
        children.addAll(stack, text)
        text.minWidth = 0.0
        isVisible = false
        clip =
            Rectangle().also {
                it.widthProperty().bind(widthProperty())
                it.heightProperty().bind(heightProperty())
            }
        Tooltip.install(this, tooltip)
    }

    /** Replaces the labels by those of [refs] (ordered as `GitRefs.at` orders them); none hides them. */
    fun show(
        refs: List<GitRef>,
        currentBranch: String?,
    ) {
        this.refs = refs
        isVisible = refs.isNotEmpty()
        val kinds = refs.mapTo(HashSet()) { it.kind }
        for ((kind, icon) in icons) {
            icon.isVisible = kind in kinds
            icon.isManaged = kind in kinds
        }
        text.text = refLabelText(refGroups(refs, currentBranch))
    }
}
