package dev.sluide.gitlog.filter

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignM

/**
 * A value of a filter popup: [value] goes into the filter, [text] is shown in the list, [short] on the button; the
 * [icon] is made for every cell that shows it.
 */
internal class FilterItem(
    val value: String,
    val text: String,
    val short: String = text,
    val icon: (() -> Node)? = null,
)

/**
 * A button of the [FilterBar] ("Branch", "User: me +1") that shows its [popup], and a clear button next to it, only
 * there while something is selected. [changed] is told of every change by the user.
 */
internal class FilterButton(
    private val title: String,
    icon: Ikon,
    items: () -> List<FilterItem>,
    freeItem: ((String) -> FilterItem?)?,
    showing: () -> Unit,
    private val changed: () -> Unit,
) : HBox() {
    /** Long values ("Branch: feature/…") are ellipsized, so that the button does not take the whole bar. */
    internal val label = Label(title).apply { maxWidth = LABEL_MAX_WIDTH }

    internal val popup = FilterPopup(items, freeItem) { selectionChanged() }

    internal val button =
        Button(
            null,
            HBox(FontIcon(icon), label, FontIcon(MaterialDesignM.MENU_DOWN)).apply { styleClass += LABEL },
        ).apply {
            styleClass += "git-filter-button"
            setOnAction {
                showing()
                popup.show(this)
            }
        }

    internal val clear =
        Button(null, FontIcon(MaterialDesignC.CLOSE)).apply {
            styleClass += "git-filter-clear"
            tooltip = Tooltip("Clear")
            setOnAction {
                popup.clear()
                selectionChanged()
            }
        }

    /** The values selected, in list order. */
    val values: List<String> get() = popup.selected.map { it.value }

    private companion object {
        const val LABEL = "git-filter-label"
        const val LABEL_MAX_WIDTH = 160.0
    }

    init {
        styleClass += "git-filter"
        alignment = Pos.CENTER_LEFT
        children.addAll(button, clear)
        update()
    }

    /** Selects exactly [values] and tells. */
    fun select(values: List<String>) {
        popup.choose(values)
        selectionChanged()
    }

    /** Selects nothing, without telling. */
    fun reset() {
        popup.hide()
        popup.clear()
        update()
    }

    private fun selectionChanged() {
        update()
        changed()
    }

    private fun update() {
        val selected = popup.selected
        label.text =
            when (selected.size) {
                0 -> title
                1 -> "$title: ${selected.first().short}"
                else -> "$title: ${selected.first().short} +${selected.size - 1}"
            }
        clear.isVisible = selected.isNotEmpty()
        clear.isManaged = selected.isNotEmpty()
    }
}
