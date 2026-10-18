package dev.sluide.gitlog.area

import dev.sluide.gitlog.ui.contains
import javafx.css.PseudoClass
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC

private val SELECTED: PseudoClass = PseudoClass.getPseudoClass("selected")

/**
 * A tab in the header of the git log area (ADR 0046): its [title] with a [tip], for a history a close button (×). A
 * primary click selects it ([onSelect]), a middle click or the button closes it ([onClose]); the clicks go no further,
 * so the header does not expand or collapse the area for them. [selected] marks it in the accent colour. The close
 * button stays whole when the tab is narrower than it wants; the title gives way.
 */
internal class AreaTab(
    title: String,
    tip: String?,
    closable: Boolean,
) : HBox() {
    var onSelect: () -> Unit = {}
    var onClose: () -> Unit = {}

    /** The title, at most [MAX_TITLE_WIDTH] wide, shortened with an ellipsis (the tooltip has the path). */
    internal val label =
        Label(title).apply {
            styleClass += "git-log-tab-title"
            maxWidth = MAX_TITLE_WIDTH
        }

    internal val closeButton: Button? =
        if (closable) {
            Button(null, FontIcon(MaterialDesignC.CLOSE)).apply {
                styleClass += "git-log-tab-close"
                tooltip = Tooltip("Close")
                isFocusTraversable = false
                minWidth = USE_PREF_SIZE
                setOnAction { onClose() }
            }
        } else {
            null
        }

    /** Shows the [tip], if there is one. */
    internal val tooltip: Tooltip? = tip?.let(::Tooltip)

    /** Whether this is the selected tab. */
    var selected = false
        set(value) {
            field = value
            pseudoClassStateChanged(SELECTED, value)
        }

    init {
        styleClass += "git-log-tab"
        alignment = Pos.CENTER_LEFT
        children += label
        closeButton?.let(children::add)
        tooltip?.let { Tooltip.install(this, it) }
        setOnMouseClicked { event ->
            val onButton = closeButton?.contains(event.target) == true
            when {
                onButton -> {}

                event.button == MouseButton.PRIMARY -> {
                    onSelect()
                }

                event.button == MouseButton.MIDDLE && closable -> {
                    onClose()
                }
            }
            event.consume()
        }
    }

    private companion object {
        const val MAX_TITLE_WIDTH = 180.0
    }
}
