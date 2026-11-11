package dev.sluide.ebooks.view

import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.control.SplitPane
import javafx.scene.layout.StackPane

/**
 * The reader's split: Contents panel (when [contentsShown]), the page, and the right pane when it has children. The
 * divider position survives hiding the panel; it starts at [CONTENTS_WIDTH] (less in a narrow window) once the split
 * has a width. The right pane opens at [RIGHT_WIDTH] (at most 35 % of the split) and keeps the width it was left at. FX thread only; [alive] says whether the view still exists (for deferred work).
 */
internal class ReaderSplit(
    private val contents: Node,
    private val page: Node,
    private val right: StackPane,
    private val contentsShown: () -> Boolean,
    private val alive: () -> Boolean,
) {
    val node = SplitPane()
    private var leftDivider = 0.22
    private var sized = false

    /** The right pane's width the user left it at, kept while it is hidden; null until it was shown. */
    private var rightWidth: Double? = null

    /** The right pane was added and still waits for the split's width to get its own. */
    private var rightPending = false

    init {
        SplitPane.setResizableWithParent(contents, false)
        SplitPane.setResizableWithParent(right, false)
        node.widthProperty().subscribe { width ->
            sizeFirst(width.toDouble())
            if (rightPending) sizeRight()
        }
        sync()
    }

    private fun sizeFirst(width: Double) {
        if (sized || width <= 0 || node.dividers.isEmpty() || node.items.firstOrNull() !== contents) return
        sized = true
        leftDivider = minOf(CONTENTS_WIDTH, width * NARROW_SHARE) / width
        node.setDividerPosition(0, leftDivider)
    }

    /** Shows the items that are due now. */
    fun sync() {
        val showContents = contentsShown()
        if (node.items.contains(contents) && node.dividers.isNotEmpty()) leftDivider = node.dividers[0].position
        if (node.items.contains(right) && right.width > 0) rightWidth = right.width
        val items = mutableListOf<Node>()
        if (showContents) items += contents
        items += page
        if (right.children.isNotEmpty()) items += right
        if (items == node.items.toList()) return
        node.items.setAll(items)
        if (showContents) {
            node.setDividerPosition(0, leftDivider)
            Platform.runLater { if (alive() && node.items.contains(contents)) node.setDividerPosition(0, leftDivider) }
        }
        if (items.last() === right) {
            rightPending = true
            sizeRight()
            Platform.runLater { if (alive()) sizeRight() }
        }
    }

    /** Gives the right pane its kept width, or [RIGHT_WIDTH] (less in a narrow window) the first time. */
    private fun sizeRight() {
        val width = node.width
        if (width <= 0 || node.items.lastOrNull() !== right || node.dividers.isEmpty()) return
        val wanted = rightWidth ?: minOf(RIGHT_WIDTH, width * RIGHT_SHARE)
        node.setDividerPosition(node.dividers.lastIndex, 1 - minOf(wanted, width * RIGHT_SHARE_MAX) / width)
        rightPending = false
    }

    companion object {
        const val CONTENTS_WIDTH = 240.0
        const val RIGHT_WIDTH = 320.0
        private const val NARROW_SHARE = 0.3
        private const val RIGHT_SHARE = 0.35
        private const val RIGHT_SHARE_MAX = 0.6
    }
}
