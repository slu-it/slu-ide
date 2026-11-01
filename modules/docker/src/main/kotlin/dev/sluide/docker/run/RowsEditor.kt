package dev.sluide.docker.run

import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/** One row of a [RowsEditor]: the [box] shown and how to read its [value]. */
internal class EditorRow<R>(
    val box: HBox,
    val value: () -> R,
)

/**
 * A list of rows with a "−" button each and a "+" button below: the editor of the Run dialog's ports, variables and
 * volumes. [newRow] builds an empty row for [setAll] and "+"; it gets the function to call when its input changes.
 */
internal class RowsEditor<R>(
    private val addTitle: String,
    private val onChange: () -> Unit,
    private val newRow: (R?, () -> Unit) -> EditorRow<R>,
) {
    private val rows = mutableListOf<EditorRow<R>>()
    private val list = VBox(3.0)
    private val add = Button("+").apply { setOnAction { addRow(null) } }

    val node =
        VBox(
            3.0,
            list,
            HBox(6.0, add, Label(addTitle).apply { styleClass += "docker-row-detail" }).apply {
                alignment = javafx.geometry.Pos.CENTER_LEFT
            },
        ).apply { padding = Insets(0.0) }

    /** The "+" button. */
    val addButton: Button get() = add

    /** The rows' values, in order. */
    fun values(): List<R> = rows.map { it.value() }

    /** Replaces the rows with one per [values]; counts as a change. */
    fun setAll(values: List<R>) {
        rows.clear()
        list.children.clear()
        values.forEach { addRow(it, notify = false) }
        onChange()
    }

    private fun addRow(
        initial: R?,
        notify: Boolean = true,
    ) {
        val row = newRow(initial, onChange)
        val remove =
            Button("−").apply {
                setOnAction {
                    rows -= row
                    list.children -= row.box
                    onChange()
                }
            }
        row.box.children += remove
        HBox.setHgrow(row.box, Priority.ALWAYS)
        rows += row
        list.children += row.box
        if (notify) onChange()
    }
}
