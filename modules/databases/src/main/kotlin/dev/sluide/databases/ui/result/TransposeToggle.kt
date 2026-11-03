package dev.sluide.databases.ui.result

import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import org.kordamp.ikonli.javafx.FontIcon

/** The toolbar toggle that shows the rows of the page as columns (record view). */
internal class TransposeToggle(
    onChange: (Boolean) -> Unit,
) {
    val node =
        ToggleButton().apply {
            graphic = FontIcon("mdi2t-table-pivot")
            styleClass += "result-button"
            isFocusTraversable = false
            tooltip = Tooltip(TIP)
            setOnAction { onChange(isSelected) }
        }

    /** Back to the normal grid, without telling the listener (a new load replaces the content anyway). */
    fun reset() {
        node.isSelected = false
    }

    /** Off while edits are pending: the transposed grid cannot show them. */
    fun lock(locked: Boolean) {
        node.isDisable = locked
        node.tooltip.text = if (locked) ResultTabsLogic.PENDING_TIP else TIP
    }

    private companion object {
        const val TIP = "Transpose"
    }
}
