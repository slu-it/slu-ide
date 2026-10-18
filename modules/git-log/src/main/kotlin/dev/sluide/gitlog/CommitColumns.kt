package dev.sluide.gitlog

import dev.sluide.gitlog.data.GitLogRow
import dev.sluide.gitlog.data.GraphRow
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.scene.control.ConstrainedColumnResizeBase
import javafx.scene.control.TableColumn
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.skin.TableViewSkin
import javafx.scene.control.skin.VirtualFlow
import javafx.util.Callback

/** A column of the commits with the texts [value] gives, not sortable; also of a file's history (ADR 0046). */
internal fun column(
    title: String,
    width: Double,
    value: (GitLogRow) -> String,
) = TableColumn<GitLogRow, String>(title).apply {
    prefWidth = width
    // Sorting would mix the order the pages are loaded in.
    isSortable = false
    setCellValueFactory { ReadOnlyStringWrapper(value(it.value)).readOnlyProperty }
}

/**
 * The subject with the commit graph left of it (ADR 0036) and the ref labels at its right end (ADR 0037); rows without
 * a graph ([GraphRow.NONE], e.g. of a file's history) have the subject at the left.
 */
internal fun subjectColumn() =
    TableColumn<GitLogRow, GitLogRow>("Subject").apply {
        prefWidth = 600.0
        minWidth = 80.0
        isSortable = false
        setCellValueFactory { ReadOnlyObjectWrapper(it.value).readOnlyProperty }
        setCellFactory { GraphCell() }
    }

/** Reports the last visible row after every layout and scroll, so the next page is loaded in time. */
internal class PagingSkin(
    table: TableView<GitLogRow>,
    private val shown: (Int) -> Unit,
) : TableViewSkin<GitLogRow>(table) {
    // Called by the super constructor: uses `shown` only later, in layouts and scrolls.
    override fun createVirtualFlow(): VirtualFlow<TableRow<GitLogRow>> =
        object : VirtualFlow<TableRow<GitLogRow>>() {
            override fun layoutChildren() {
                super.layoutChildren()
                report()
            }

            override fun scrollPixels(delta: Double): Double = super.scrollPixels(delta).also { report() }

            private fun report() {
                val index = lastVisibleCell?.index ?: return
                if (index >= 0) shown(index)
            }
        }
}

/**
 * Keeps the columns as wide as the table: a resized table widens or narrows only the first column (the subject), so
 * the others keep their widths ([fitColumnWidths]); dragging a column's edge resizes the next column as well, and the
 * new widths of the others become the widths they return to.
 */
internal object SubjectTakesTheRest :
    ConstrainedColumnResizeBase(),
    Callback<TableView.ResizeFeatures<*>, Boolean> {
    override fun call(features: TableView.ResizeFeatures<*>): Boolean {
        @Suppress("UNCHECKED_CAST")
        val resize = features as TableView.ResizeFeatures<GitLogRow>
        val columns = resize.table.visibleLeafColumns
        if (columns.isEmpty()) return false
        if (resize.column != null) {
            val resized = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_NEXT_COLUMN.call(resize)
            // Wanted widths are kept as preferred widths; setting them does not change the current ones.
            columns.drop(1).forEach { it.prefWidth = it.width }
            return resized
        }
        val others = columns.drop(1)
        val widths =
            fitColumnWidths(
                resize.contentWidth,
                columns.first().minWidth,
                others.map { it.prefWidth },
                others.map { it.minWidth },
            )
        columns.zip(widths).forEach { (column, width) -> resize.setColumnWidth(column, width) }
        return true
    }
}

/**
 * The widths of a first column that takes the rest of [contentWidth] and of the [others] (their wanted widths), whose
 * minimum widths are [otherMinWidths]. The others keep their widths while the first column can be at least
 * [firstMinWidth] wide; below that they shrink proportionally towards their minimum widths, so the last column stays
 * in view. Narrower than all minimum widths together, every column is at its minimum and the last ones are cut off at
 * the right (a constrained table has no horizontal scroll bar).
 */
internal fun fitColumnWidths(
    contentWidth: Double,
    firstMinWidth: Double,
    others: List<Double>,
    otherMinWidths: List<Double>,
): List<Double> {
    val wanted = others.sum()
    if (contentWidth - wanted >= firstMinWidth) return listOf(contentWidth - wanted) + others
    val shrinkable = others.zip(otherMinWidths).sumOf { (width, min) -> maxOf(0.0, width - min) }
    val excess = wanted - (contentWidth - firstMinWidth)
    val share = if (shrinkable > 0) minOf(1.0, excess / shrinkable) else 1.0
    val shrunk = others.zip(otherMinWidths).map { (width, min) -> maxOf(min, width - maxOf(0.0, width - min) * share) }
    return listOf(maxOf(firstMinWidth, contentWidth - shrunk.sum())) + shrunk
}
