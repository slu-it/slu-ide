package dev.sluide.coloring.ui

import dev.sluide.coloring.color.ColorFormat
import dev.sluide.coloring.color.FormatGroup
import dev.sluide.coloring.color.FormattedColor
import dev.sluide.coloring.color.formatGroups
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.util.Subscription

/** The current color in the CSS notations and the design tool notations, each with a copy button. */
internal class FormatsSection(
    private val state: ColoringState,
) {
    private class Row(
        val text: TextField,
        val marker: Label,
    )

    private val column = VBox(4.0).apply { styleClass += "coloring-formats" }
    private var rows = emptyList<Row>()
    private var shape = emptyList<Pair<String, ColorFormat>>()
    private val subscription: Subscription

    val node: Node = column

    init {
        subscription = state.current.subscribe { color -> refresh(formatGroups(color)) }
    }

    private fun refresh(groups: List<FormatGroup>) {
        val newShape = groups.flatMap { g -> g.entries.map { g.title to it.format } }
        if (newShape != shape) {
            shape = newShape
            rebuild(groups)
        }
        rows.zip(groups.flatMap { it.entries }).forEach { (row, entry) -> show(row, entry) }
    }

    private fun show(
        row: Row,
        entry: FormattedColor,
    ) {
        row.text.text = entry.text
        row.marker.isVisible = entry.approximate
    }

    private fun rebuild(groups: List<FormatGroup>) {
        val built = mutableListOf<Row>()
        column.children.clear()
        for (group in groups) {
            if (group.entries.isEmpty()) continue
            column.children += Label(group.title).apply { styleClass += "coloring-section-title" }
            for (entry in group.entries) {
                val text =
                    TextField().apply {
                        isEditable = false
                        styleClass += "coloring-format-text"
                        HBox.setHgrow(this, Priority.ALWAYS)
                    }
                val marker =
                    Label("≈").apply {
                        tooltip = Tooltip("Outside sRGB: mapped into the gamut")
                        styleClass += "coloring-approximate"
                    }
                val copy = copyButton { text.text }
                val label =
                    Label(entry.format.label).apply {
                        styleClass += "coloring-format-label"
                        minWidth = LABEL_WIDTH
                        prefWidth = LABEL_WIDTH
                    }
                column.children +=
                    HBox(4.0, label, text, marker, copy).apply {
                        alignment = Pos.CENTER_LEFT
                        styleClass += "coloring-format-row"
                    }
                built += Row(text, marker)
            }
        }
        rows = built
    }

    /** Releases the listeners on the window's state. */
    fun dispose() = subscription.unsubscribe()

    private companion object {
        const val LABEL_WIDTH = 64.0
    }
}
