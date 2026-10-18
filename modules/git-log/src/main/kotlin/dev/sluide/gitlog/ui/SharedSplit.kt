package dev.sluide.gitlog.ui

import javafx.geometry.Orientation
import javafx.scene.Node
import javafx.scene.control.SplitPane

/**
 * Two panes side by side (or one above the other, [orientation]) that keep their shares of the width (height) when it
 * changes: [share] for the first at first, then wherever the divider is dragged to (while the window is open). A plain
 * split pane would keep the pixel size of a pane that does not resize with it, or split the change equally between
 * panes that do.
 */
internal class SharedSplit(
    private val first: Node,
    second: Node,
    share: Double,
    orientation: Orientation = Orientation.HORIZONTAL,
) : SplitPane(first, second) {
    /** The first pane's share of the width (height); follows the divider when it is dragged. */
    var share = share
        private set

    private var laidOutSize = -1.0
    private var layingOut = false

    private val size get() = if (orientation == Orientation.VERTICAL) height else width

    /** Whether the first pane is shown; hidden, the second takes the whole size, shown again it gets its share back. */
    var firstShown = true
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                items.add(0, first)
                followDivider()
            } else {
                items.remove(first)
            }
            laidOutSize = -1.0
        }

    init {
        this.orientation = orientation
        // The skin would spread a change of the width equally over the panes that resize with it; with none it keeps
        // the divider position set before each layout at a new width.
        SplitPane.setResizableWithParent(first, false)
        SplitPane.setResizableWithParent(second, false)
        followDivider()
    }

    /** Puts the divider at the [share] and follows it from now on; there is a new one whenever the first pane is. */
    private fun followDivider() {
        setDividerPositions(share)
        // Moved outside a layout: dragged (or set). Within one the skin only fits it to the panes' minimum widths.
        dividers.single().positionProperty().subscribe { position -> if (!layingOut) this.share = position.toDouble() }
    }

    override fun layoutChildren() {
        layingOut = true
        try {
            if (size != laidOutSize) setDividerPositions(share)
            super.layoutChildren()
        } finally {
            layingOut = false
        }
        laidOutSize = size
    }
}
