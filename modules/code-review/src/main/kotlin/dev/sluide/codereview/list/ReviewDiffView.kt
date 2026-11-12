package dev.sluide.codereview.list

import dev.sluide.codereview.model.ExpandDirection
import dev.sluide.codereview.model.ReviewLineSource
import dev.sluide.codereview.model.ReviewRow
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.collections.ObservableList
import javafx.scene.Node

/**
 * The diff of a review window (ADR 0168): [rows] of a `ReviewDocument` drawn one below the other, the lines' texts
 * from its [ReviewLineSource]. The window talks to it only through this interface, so it can be built and tested
 * without the real list.
 */
internal interface ReviewDiffView {
    /** The node to put into the window. */
    val node: Node

    /** The user asked to reveal hidden lines of gap [gap] of the file at [path]. */
    var onExpand: (path: String, gap: Int, direction: ExpandDirection) -> Unit

    /** The user checked or unchecked Viewed of the file at [path]. */
    var onViewedChanged: (path: String, viewed: Boolean) -> Unit

    /** The user collapsed or expanded the file at [path] with its chevron. */
    var onCollapsedChanged: (path: String, collapsed: Boolean) -> Unit

    /** The user asked to show the diff of the file at [path] that started behind Load diff. */
    var onLoadDiff: (path: String) -> Unit

    /** The path of the file the first visible row belongs to; `null` without rows. Follows every scroll. */
    val topFile: ReadOnlyObjectProperty<String?>

    /** Scrolls so that row [index] starts [offset] pixels below the top of the viewport (the app's own scroll). */
    fun scrollToRow(
        index: Int,
        offset: Double = 0.0,
    )

    /**
     * Scrolls so that row [index] is fully visible below the sticky file header (for jumps to a change, a file or a
     * find match).
     */
    fun reveal(index: Int) = scrollToRow(index, 0.0)

    /**
     * The first visible row and how far its top is below the viewport's top (negative when cut off). Right after
     * [scrollToRow] or [reveal] this is the requested position, before it is laid out; once the layout has settled
     * or the user scrolls it is the real one (a row too near the end to reach its offset reports where it is).
     */
    fun firstVisible(): Pair<Int, Double>

    /** The node showing row [index], `null` while it is not shown. */
    fun rowCell(index: Int): Node?
}

/** The diff view of the review window. */
internal fun createDiffView(
    rows: ObservableList<ReviewRow>,
    lines: ReviewLineSource,
): ReviewDiffView = ReviewDiffList(rows, lines)
