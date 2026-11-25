package dev.sluide.pdf.view

import javafx.application.Platform
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.scene.Node
import javafx.scene.control.SplitPane
import javafx.scene.layout.StackPane

/**
 * The view's split (a copy of the ebook reader's `ReaderSplit`): the [left] panel while [leftShown], the [centre] and
 * the [right] panel while [rightShown]; both panels are empty and hidden until a feature fills and shows them. The
 * panels open at [LEFT_WIDTH] and [RIGHT_WIDTH] (less in a narrow window) the first time and keep the width they were
 * left at while hidden. FX thread only; [alive] says whether the view still exists (for deferred work).
 */
internal class PdfSplit(
    private val centre: Node,
    private val alive: () -> Boolean,
) {
    val left = StackPane().apply { styleClass += "pdf-left" }
    val right = StackPane().apply { styleClass += "pdf-right" }
    val node = SplitPane()
    private val leftShownWrapper = ReadOnlyBooleanWrapper(false)
    private val rightShownWrapper = ReadOnlyBooleanWrapper(false)
    val leftShown: ReadOnlyBooleanProperty = leftShownWrapper.readOnlyProperty
    val rightShown: ReadOnlyBooleanProperty = rightShownWrapper.readOnlyProperty

    /** The panels' widths the user left them at, kept while hidden; null until shown with a width. */
    private var leftWidth: Double? = null
    private var rightWidth: Double? = null

    /** A panel was shown and still waits for the split's width to get its own. */
    private var pending = false

    init {
        SplitPane.setResizableWithParent(left, false)
        SplitPane.setResizableWithParent(right, false)
        node.widthProperty().subscribe { _ -> if (pending) size() }
        sync()
    }

    fun showLeft(show: Boolean) {
        leftShownWrapper.set(show)
        sync()
    }

    fun showRight(show: Boolean) {
        rightShownWrapper.set(show)
        sync()
    }

    private fun sync() {
        // The dividers' places, not the panels' widths, which are smaller by half a divider each time.
        if (node.items.contains(left) && left.width > 0) leftWidth = node.dividers.first().position * node.width
        if (node.items.contains(right) && right.width > 0) rightWidth = (1 - node.dividers.last().position) * node.width
        val items = mutableListOf<Node>()
        if (leftShown.get()) items += left
        items += centre
        if (rightShown.get()) items += right
        if (items == node.items.toList()) return
        node.items.setAll(items)
        if (items.size > 1) {
            pending = true
            size()
            Platform.runLater { if (alive()) size() }
        }
    }

    /** Gives the shown panels their kept widths, or the first ones (less in a narrow window). */
    private fun size() {
        val width = node.width
        if (width <= 0 || node.dividers.isEmpty()) return
        if (node.items.first() === left) node.setDividerPosition(0, panelShare(leftWidth, LEFT_WIDTH, width))
        if (node.items.last() === right) {
            node.setDividerPosition(node.dividers.lastIndex, 1 - panelShare(rightWidth, RIGHT_WIDTH, width))
        }
        pending = false
    }

    companion object {
        const val LEFT_WIDTH = 220.0
        const val RIGHT_WIDTH = 320.0
    }
}

private const val FIRST_SHARE = 0.35
private const val MAX_SHARE = 0.45

/**
 * The share of a split [splitWidth] wide that a panel takes: the width it was [kept] at, or [first] the first time (at
 * most 35 % of the split then), and never more than 45 %.
 */
internal fun panelShare(
    kept: Double?,
    first: Double,
    splitWidth: Double,
): Double = minOf(kept ?: minOf(first, splitWidth * FIRST_SHARE), splitWidth * MAX_SHARE) / splitWidth
