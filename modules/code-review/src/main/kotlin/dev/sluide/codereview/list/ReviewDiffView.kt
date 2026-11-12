package dev.sluide.codereview.list

import dev.sluide.codereview.model.ExpandDirection
import dev.sluide.codereview.model.ReviewLineSource
import dev.sluide.codereview.model.ReviewRow
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.collections.ObservableList
import javafx.scene.Node
import javafx.scene.control.ListView

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

    /** The first visible row and how far its top is below the viewport's top (negative when cut off). */
    fun firstVisible(): Pair<Int, Double>

    /** The node showing row [index], `null` while it is not shown. */
    fun rowCell(index: Int): Node?
}

/** The diff view of the review window. Until the real list exists (package P4), a plain list of the rows. */
internal fun createDiffView(
    rows: ObservableList<ReviewRow>,
    lines: ReviewLineSource,
): ReviewDiffView = PlaceholderDiffView(rows)

/** Shows each row as text; replaced by the real list. */
private class PlaceholderDiffView(
    rows: ObservableList<ReviewRow>,
) : ReviewDiffView {
    private val list = ListView(rows)
    private val top = ReadOnlyObjectWrapper<String?>(rows.firstOrNull()?.path)

    override val node: Node = list
    override var onExpand: (String, Int, ExpandDirection) -> Unit = { _, _, _ -> }
    override var onViewedChanged: (String, Boolean) -> Unit = { _, _ -> }
    override var onCollapsedChanged: (String, Boolean) -> Unit = { _, _ -> }
    override var onLoadDiff: (String) -> Unit = {}
    override val topFile: ReadOnlyObjectProperty<String?> = top.readOnlyProperty

    override fun scrollToRow(
        index: Int,
        offset: Double,
    ) {
        list.scrollTo(index)
        top.set(list.items.getOrNull(index)?.path)
    }

    override fun firstVisible(): Pair<Int, Double> = 0 to 0.0

    override fun rowCell(index: Int): Node? = null
}
