package dev.sluide.codereview.list

import dev.sluide.codereview.model.ExpandDirection
import dev.sluide.codereview.model.ReviewLineSource
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.fileicons.FILE_ICONS_CSS
import dev.sluide.fileviewers.FileViewers
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.collections.ListChangeListener
import javafx.collections.ObservableList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ScrollPane
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

    private val flow: VirtualFlow<ReviewRow, Cell<ReviewRow, Node>> =
        VirtualFlow.createVertical(rows) { row -> Cell.wrapNode(createRowCell(row, lines, actions)) }

    private var stickyRow: ReviewRow.FileHeader? = null
    private val sticky =
        StackPane().apply {
            styleClass += "review-sticky"
            isVisible = false
            maxHeight = Region.USE_PREF_SIZE
            StackPane.setAlignment(this, Pos.TOP_LEFT)
            // Leaves the scroll bar free.
            StackPane.setMargin(this, Insets(0.0, SCROLL_BAR_WIDTH, 0.0, 0.0))
        }

    init {
        styleClass += "review-diff"
        stylesheets +=
            listOf(
                checkNotNull(FileViewers::class.java.getResource("syntax.css")).toExternalForm(),
                FILE_ICONS_CSS,
                checkNotNull(ReviewDiffList::class.java.getResource("/dev/sluide/codereview/review-diff.css"))
                    .toExternalForm(),
            )
        isFocusTraversable = true
        addEventHandler(MouseEvent.MOUSE_PRESSED) { requestFocus() }
        children +=
            listOf(
                VirtualizedScrollPane(flow, ScrollPane.ScrollBarPolicy.NEVER, ScrollPane.ScrollBarPolicy.AS_NEEDED),
                sticky,
            )
        flow.visibleCells().addListener(ListChangeListener { updateTop() })
        // A scroll lays the flow out; the cells may stay the same ones.
        flow.needsLayoutProperty().addListener { _, _, needs -> if (!needs) updateTop() }
        rows.addListener(ListChangeListener { updateTop() })
        updateTop()
    }

    override fun scrollToRow(
        index: Int,
        offset: Double,
    ) {
        if (index !in rows.indices) return
        flow.showAtOffset(index, offset)
        updateTop()
    }

    override fun firstVisible(): Pair<Int, Double> = position() ?: (0 to 0.0)

    override fun rowCell(index: Int): Node? = flow.getCellIfVisible(index).map { it.node }.orElse(null)

    /** The first visible row and its top below the viewport's top; `null` while nothing is laid out. */
    private fun position(): Pair<Int, Double>? {
        if (rows.isEmpty() || flow.visibleCells().isEmpty()) return null
        val index = flow.firstVisibleIndex
        val cell = flow.getCellIfVisible(index).orElse(null) ?: return null
        return index to flow.cellToViewport(cell, 0.0, 0.0).y
    }

    private fun updateTop() {
        val position = position()
        val row = position?.let { rows.getOrNull(it.first) }
        top.set(row?.path ?: rows.firstOrNull()?.path)
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
            if (header != stickyRow) {
                stickyRow = header
                sticky.children.setAll(createHeaderCell(header, actions))
            }
            sticky.isVisible = true
        }
    }

    private fun headerOf(
        index: Int,
        path: String,
    ): ReviewRow.FileHeader? {
        var i = index
        while (i >= 0 && rows[i].path == path) {
            (rows[i] as? ReviewRow.FileHeader)?.let { return it }
            i--
        }
        return null
    }

    private companion object {
        const val SCROLL_BAR_WIDTH = 14.0
    }
}
