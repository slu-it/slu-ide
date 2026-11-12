package dev.sluide.codereview.list

import dev.sluide.codereview.find.FindMatch
import dev.sluide.codereview.model.ExpandDirection
import dev.sluide.codereview.model.ReviewLineSource
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.fileicons.FILE_ICONS_CSS
import dev.sluide.fileviewers.FileViewers
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.collections.ListChangeListener
import javafx.collections.ObservableList
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ScrollPane
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import org.fxmisc.flowless.Cell
import org.fxmisc.flowless.VirtualFlow
import org.fxmisc.flowless.VirtualizedScrollPane

/**
 * The diff of the review window (ADR 0168): a virtualized list of [rows] whose rows differ in height (long lines
 * wrap, there is no horizontal scroll bar). The header of the file the first visible row belongs to stays at the top.
 */
internal class ReviewDiffList(
    private val rows: ObservableList<ReviewRow>,
    lines: ReviewLineSource,
) : StackPane(),
    ReviewDiffView {
    override var onExpand: (path: String, gap: Int, direction: ExpandDirection) -> Unit = { _, _, _ -> }
    override var onViewedChanged: (path: String, viewed: Boolean) -> Unit = { _, _ -> }
    override var onCollapsedChanged: (path: String, collapsed: Boolean) -> Unit = { _, _ -> }
    override var onLoadDiff: (path: String) -> Unit = {}
    override var lineActions: LineActions? = null

    private val top = ReadOnlyObjectWrapper<String?>(rows.firstOrNull()?.path)
    override val topFile: ReadOnlyObjectProperty<String?> = top.readOnlyProperty
    override val node: Node = this

    // The cells call through the properties, so that a callback set later is used too.
    private val actions =
        RowActions(
            expand = { path, gap, direction -> onExpand(path, gap, direction) },
            viewed = { path, viewed -> onViewedChanged(path, viewed) },
            collapsed = { path, collapsed -> onCollapsedChanged(path, collapsed) },
            loadDiff = { path -> onLoadDiff(path) },
        )

    private val highlights = FindHighlights()

    private val flow: VirtualFlow<ReviewRow, Cell<ReviewRow, Node>> =
        VirtualFlow.createVertical(rows) { row ->
            Cell.wrapNode(
                createRowCell(row, lines, actions).also {
                    interactions.paint(row, it)
                    highlights.paint(row, it)
                },
            )
        }

    private val interactions =
        DiffInteractions(
            rows,
            lines,
            object : InteractionHost {
                override val owner: Node get() = this@ReviewDiffList

                override fun visibleIndices() = this@ReviewDiffList.visibleIndices()

                override fun cell(index: Int) = rowCell(index)

                override fun indexOfCell(node: Node): Int {
                    if (node === sticky) return stickyIndex.takeIf { rows.getOrNull(it) == stickyRow } ?: -1
                    return visibleIndices().firstOrNull { rowCell(it) === node } ?: -1
                }

                override fun viewportRow(): Int {
                    val below = if (sticky.isVisible) sticky.prefHeight(-1.0) else 0.0
                    return visibleIndices().firstOrNull { i ->
                        flow
                            .getCellIfVisible(
                                i,
                            ).map { flow.cellToViewport(it, 0.0, 0.0).y >= below - 1.0 }
                            .orElse(false)
                    } ?: firstVisible().first
                }

                override fun firstVisibleRow() = firstVisible().first

                override fun reveal(index: Int) = this@ReviewDiffList.reveal(index)

                override fun setViewed(
                    path: String,
                    viewed: Boolean,
                ) = onViewedChanged(path, viewed)
            },
        ) { lineActions }

    private var stickyRow: ReviewRow.FileHeader? = null
    private val sticky =
        StackPane().apply {
            styleClass += "review-sticky"
            isVisible = false
            maxHeight = Region.USE_PREF_SIZE
            StackPane.setAlignment(this, Pos.TOP_LEFT)
        }

    /** The row of [stickyRow] in [rows] as last found, so that the next lookup starts there. */
    private var stickyIndex = -1

    /** Where the list is scrolled to as far as the app knows: kept across changes of the rows, see [followChange]. */
    private class Remembered(
        val index: Int,
        val offset: Double,
        val key: RowKey,
    )

    private var remembered: Remembered? = null

    init {
        styleClass += "review-diff"
        // As wide as the list, with or without a scroll bar.
        sticky.prefWidthProperty().bind(flow.widthProperty())
        sticky.maxWidth = Region.USE_PREF_SIZE
        stylesheets +=
            listOf(
                checkNotNull(FileViewers::class.java.getResource("syntax.css")).toExternalForm(),
                FILE_ICONS_CSS,
                checkNotNull(ReviewDiffList::class.java.getResource("/dev/sluide/codereview/review-diff.css"))
                    .toExternalForm(),
            )
        isFocusTraversable = true
        addEventHandler(MouseEvent.MOUSE_PRESSED) { requestFocus() }
        addEventFilter(MouseEvent.MOUSE_PRESSED, interactions::onMousePressed)
        addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, interactions::onContextMenu)
        addEventHandler(KeyEvent.KEY_PRESSED, interactions::onKey)
        children +=
            listOf(
                VirtualizedScrollPane(flow, ScrollPane.ScrollBarPolicy.NEVER, ScrollPane.ScrollBarPolicy.AS_NEEDED),
                sticky,
            )
        // A layout shows where the list is now.
        flow.needsLayoutProperty().addListener { _, _, needs -> if (!needs) updateTop() }
        // After Flowless's own listener, so that its change of the position is the one to correct.
        rows.addListener(ListChangeListener { change -> followChange(change) })
        rows.addListener(ListChangeListener { interactions.rowsChanged() })
        updateTop()
    }

    override fun clearSelection() = interactions.clearSelection()

    override fun showMatches(
        matches: List<FindMatch>,
        current: Int?,
    ) {
        highlights.set(rows, matches, current)
        for (i in visibleIndices()) rowCell(i)?.let { highlights.paint(rows[i], it) }
    }

    private fun visibleIndices() =
        if (flow.visibleCells().isEmpty()) IntRange.EMPTY else flow.firstVisibleIndex..flow.lastVisibleIndex

    override fun scrollToRow(
        index: Int,
        offset: Double,
    ) {
        if (index !in rows.indices) return
        flow.showAtOffset(index, offset)
        // The app's own scroll is the position to keep, also when rows change before the next layout.
        remembered = Remembered(index, offset, rowKey(rows[index]))
    }

    override fun reveal(index: Int) {
        val row = rows.getOrNull(index) ?: return
        if (row is ReviewRow.FileHeader) return scrollToRow(index, 0.0)
        // The header of the file is the sticky one once the row is at the top.
        val header = headerOf(index, row.path) ?: return scrollToRow(index, 0.0)
        showSticky(header)
        sticky.applyCss()
        scrollToRow(index, sticky.prefHeight(-1.0))
    }

    override fun firstVisible(): Pair<Int, Double> =
        remembered?.let { it.index to it.offset } ?: position() ?: (0 to 0.0)

    override fun isFullyVisible(index: Int): Boolean {
        val cell = flow.getCellIfVisible(index).orElse(null) ?: return false
        val top = flow.cellToViewport(cell, 0.0, 0.0).y
        val covered = if (sticky.isVisible) sticky.prefHeight(-1.0) else 0.0
        return top >= covered - 0.5 && top + cell.node.layoutBounds.height <= flow.height + 0.5
    }

    override fun rowCell(index: Int): Node? = flow.getCellIfVisible(index).map { it.node }.orElse(null)

    init {
        // Flowless computes visibleCells (read by position and rowCell) only while it is observed.
        flow.visibleCells().addListener(ListChangeListener { })
    }

    /** The first visible row and its top below the viewport's top; `null` while nothing is laid out. */
    private fun position(): Pair<Int, Double>? {
        if (rows.isEmpty() || flow.visibleCells().isEmpty()) return null
        val index = flow.firstVisibleIndex
        val cell = flow.getCellIfVisible(index).orElse(null) ?: return null
        return index to flow.cellToViewport(cell, 0.0, 0.0).y
    }

    private fun updateTop() {
        val position = position()
        // While the flow is between two states it knows no first row: keep what was shown.
        if (position == null && rows.isNotEmpty()) return
        remember(position)
        val row = position?.let { rows.getOrNull(it.first) }
        top.set(row?.path)
        val header =
            if (position != null && row != null && (row !is ReviewRow.FileHeader || position.second < 0)) {
                headerOf(position.first, row.path)
            } else {
                null
            }
        if (header == null) {
            sticky.isVisible = false
            stickyRow = null
        } else {
            showSticky(header)
            sticky.isVisible = true
        }
    }

    private fun remember(position: Pair<Int, Double>?) {
        val index = position?.first ?: return
        val row = rows.getOrNull(index) ?: return
        remembered = Remembered(index, position.second, rowKey(row))
    }

    /**
     * Keeps the scroll position when rows change at or above the first visible row: Flowless puts a position inside a
     * replaced range at the start of the change, so the row that was first is looked up again (by its [rowKey]) and
     * shown where it was. A change below does not move it; without the row (it was expanded away) the row at the start
     * of the change takes its place.
     *
     * Known limit: a scroll by the user that is not laid out yet is unknown to the list (Flowless's scroll estimate
     * changes during the layout, not at the scroll), so a row change at or above the old first row in the same pulse
     * puts the list back where the last layout left it, for that one pulse. Telling the user's scroll from the app's
     * by a flag set on `ScrollEvent`s would be worse: it misses the scroll bar's drag and keys, and a flag that stays
     * set by an event that scrolled nothing would keep a later change from being followed.
     */
    private fun followChange(change: ListChangeListener.Change<out ReviewRow>) {
        if (rows.isEmpty()) {
            remembered = null
            return
        }
        val at = remembered
        if (at == null) return scrollToRow(0, 0.0)
        var from = Int.MAX_VALUE
        // Where the row can be now: where the changes before it shifted it to, or in what they added.
        var shifted = at.index
        val added = mutableListOf<IntRange>()
        while (change.next()) {
            from = minOf(from, change.from)
            added += change.from until change.from + change.addedSize
            if (change.from + change.removedSize <= shifted) shifted += change.addedSize - change.removedSize
        }
        if (from > at.index) return
        val index =
            (listOf(shifted) + added.flatMap { it })
                .firstOrNull { it in rows.indices && rowKey(rows[it]) == at.key }
                ?: from.coerceAtMost(rows.lastIndex)
        flow.showAtOffset(index, at.offset)
        remembered = Remembered(index, at.offset, rowKey(rows[index]))
    }

    private fun showSticky(header: ReviewRow.FileHeader) {
        if (header == stickyRow) return
        stickyRow = header
        sticky.children.setAll(createHeaderCell(header, actions))
    }

    /** The header row of the file of row [index] at [path]: the current sticky one while it still is that. */
    private fun headerOf(
        index: Int,
        path: String,
    ): ReviewRow.FileHeader? {
        val known = rows.getOrNull(stickyIndex)
        if (stickyIndex in 0..index && known is ReviewRow.FileHeader && known.path == path) return known
        var i = index
        while (i >= 0 && rows[i].path == path) {
            val row = rows[i]
            if (row is ReviewRow.FileHeader) {
                stickyIndex = i
                return row
            }
            i--
        }
        return null
    }
}
