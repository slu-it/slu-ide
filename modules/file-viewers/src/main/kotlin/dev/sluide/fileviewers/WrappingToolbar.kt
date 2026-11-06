package dev.sluide.fileviewers

import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.collections.ObservableList
import javafx.css.CssMetaData
import javafx.css.PseudoClass
import javafx.css.StyleConverter
import javafx.css.Styleable
import javafx.css.StyleableDoubleProperty
import javafx.css.StyleableProperty
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.scene.Node
import javafx.scene.control.Separator
import javafx.scene.layout.Region

/**
 * A toolbar at the top of a view that wraps instead of cutting off its right end when the view is narrower
 * (ADR 0135). It replaces an `HBox` with a growing spacer: [leading] holds the nodes before the spacer, [trailing] the
 * nodes after it. The trailing nodes stay at the right end of the first row; the leading nodes fill the first row up
 * to them and continue on further rows, left-aligned. Only when the trailing nodes alone are wider than the toolbar do
 * they wrap themselves, right-aligned, and the leading nodes start on the row after them.
 *
 * Each node is a unit that wraps as a whole (a nested `HBox` moves as a group). Nodes on a row are [hgap] apart plus
 * their [margins][setMargin], as in an `HBox`; a node starting a row drops its left margin; the groups are at least
 * twice [hgap], and at least 12 px, apart (the group distance replaces the first trailing node's left margin). A node
 * belongs to at most one of [leading] and [trailing]: remove it from one before adding it to the other. A [Separator] at a row's start or end takes no space and gets
 * the pseudo-class `row-edge`, which hides it. Rows are [vgap] apart, as tall as their tallest node, and centre their
 * nodes. Unmanaged nodes are skipped. The minimum width is the widest node's, so the toolbar never forces a wide view.
 * FX thread.
 */
class WrappingToolbar : Region() {
    /** The nodes at the left, before the old spacer; modifiable. */
    val leading: ObservableList<Node> = FXCollections.observableArrayList()

    /** The nodes at the right end of the first row, after the old spacer; modifiable. */
    val trailing: ObservableList<Node> = FXCollections.observableArrayList()

    private val hgapStyleable =
        object : StyleableDoubleProperty(0.0) {
            override fun invalidated() = requestLayout()

            override fun getBean() = this@WrappingToolbar

            override fun getName() = "hgap"

            override fun getCssMetaData(): CssMetaData<WrappingToolbar, Number> = HGAP
        }

    private val vgapStyleable =
        object : StyleableDoubleProperty(DEFAULT_VGAP) {
            override fun invalidated() = requestLayout()

            override fun getBean() = this@WrappingToolbar

            override fun getName() = "vgap"

            override fun getCssMetaData(): CssMetaData<WrappingToolbar, Number> = VGAP
        }

    /** The horizontal gap between the nodes of a row (CSS `-fx-hgap`, default 0). */
    fun hgapProperty(): StyleableDoubleProperty = hgapStyleable

    var hgap: Double
        get() = hgapStyleable.get()
        set(value) = hgapStyleable.set(value)

    /** The vertical gap between rows (CSS `-fx-vgap`, default 2). */
    fun vgapProperty(): StyleableDoubleProperty = vgapStyleable

    var vgap: Double
        get() = vgapStyleable.get()
        set(value) = vgapStyleable.set(value)

    init {
        val sync =
            ListChangeListener<Node> { change ->
                while (change.next()) change.removed.forEach { it.pseudoClassStateChanged(ROW_EDGE, false) }
                children.setAll(leading + trailing)
            }
        leading.addListener(sync)
        trailing.addListener(sync)
    }

    override fun getContentBias(): Orientation = Orientation.HORIZONTAL

    override fun getUserAgentStylesheet(): String = USER_AGENT_STYLESHEET

    override fun getCssMetaData(): List<CssMetaData<out Styleable, *>> = getClassCssMetaData()

    override fun computeMinWidth(height: Double): Double =
        snappedLeftInset() + snappedRightInset() +
            ((units(leading) + units(trailing)).maxOfOrNull { it.minWidth + it.rightMargin } ?: 0.0)

    override fun computePrefWidth(height: Double): Double =
        snappedLeftInset() + snappedRightInset() +
            toolbarPrefWidth(units(leading), units(trailing), snapSpaceX(hgap), groupGap())

    override fun computeMinHeight(width: Double): Double = computePrefHeight(width)

    override fun computePrefHeight(width: Double): Double {
        val outer = if (width < 0) prefWidth(-1.0) else width
        val rows = rowsFor(outer - snappedLeftInset() - snappedRightInset())
        return snappedTopInset() + snappedBottomInset() + rows.heights.sum() + rows.gaps()
    }

    override fun layoutChildren() {
        val left = snappedLeftInset()
        val rows = rowsFor(width - left - snappedRightInset())
        var y = snappedTopInset()
        val tops =
            rows.heights.map { height ->
                y.also { y += height + snapSpaceY(vgap) }
            }
        for ((node, place) in rows.placed) {
            val edge = node is Separator && !place.shown
            if (node.pseudoClassStates.contains(ROW_EDGE) != edge) {
                node.pseudoClassStateChanged(ROW_EDGE, edge)
                // Hidden in this pulse already, not only after the next CSS pass.
                node.applyCss()
            }
            val row = place.row.coerceAtMost(tops.lastIndex).coerceAtLeast(0)
            val top = tops.getOrElse(row) { snappedTopInset() }
            val rowHeight = rows.heights.getOrElse(row) { 0.0 }
            if (!place.shown) {
                node.resizeRelocate(snapPositionX(left + place.x), top, 0.0, rowHeight)
                continue
            }
            val margin = getMargin(node) ?: Insets.EMPTY
            val inner = rowHeight - margin.top - margin.bottom
            val height =
                snapSizeY(inner.coerceAtMost(node.maxHeight(place.width)).coerceAtLeast(node.minHeight(place.width)))
            val nodeY = top + margin.top + (inner - height) / 2
            node.resizeRelocate(snapPositionX(left + place.x), snapPositionY(nodeY), place.width, height)
        }
        children.filter { !it.isManaged }.forEach { it.pseudoClassStateChanged(ROW_EDGE, false) }
    }

    /** The managed nodes with their places, and the height of each row, for an [available] content width. */
    private fun rowsFor(available: Double): LaidOut {
        val leadingNodes = leading.filter { it.isManaged }
        val trailingNodes = trailing.filter { it.isManaged }
        val rows = toolbarRows(available, units(leading), units(trailing), snapSpaceX(hgap), groupGap())
        val placed = leadingNodes.zip(rows.leading) + trailingNodes.zip(rows.trailing)
        val heights = DoubleArray(rows.count)
        for ((node, place) in placed) {
            if (!place.shown) continue
            val margin = getMargin(node) ?: Insets.EMPTY
            heights[place.row] =
                maxOf(heights[place.row], snapSizeY(node.prefHeight(place.width)) + margin.top + margin.bottom)
        }
        return LaidOut(placed, heights.toList())
    }

    private inner class LaidOut(
        val placed: List<Pair<Node, UnitPlace>>,
        val heights: List<Double>,
    ) {
        fun gaps() = if (heights.isEmpty()) 0.0 else snapSpaceY(vgap) * (heights.size - 1)
    }

    private fun units(nodes: List<Node>): List<ToolbarUnit> =
        nodes.filter { it.isManaged }.map { node ->
            val margin = getMargin(node) ?: Insets.EMPTY
            ToolbarUnit(
                prefWidth = snapSizeX(node.prefWidth(-1.0)),
                minWidth = snapSizeX(node.minWidth(-1.0)),
                leftMargin = snapSpaceX(margin.left),
                rightMargin = snapSpaceX(margin.right),
                separator = node is Separator,
            )
        }

    private fun groupGap() = maxOf(2 * snapSpaceX(hgap), MIN_GROUP_DISTANCE)

    companion object {
        /** The least distance between the leading and the trailing nodes on the first row. */
        private const val MIN_GROUP_DISTANCE = 12.0

        private const val DEFAULT_VGAP = 2.0
        private const val MARGIN = "wrapping-toolbar-margin"
        private val ROW_EDGE: PseudoClass = PseudoClass.getPseudoClass("row-edge")

        private val USER_AGENT_STYLESHEET =
            checkNotNull(
                WrappingToolbar::class.java.getResource("/dev/sluide/fileviewers/wrapping-toolbar.css"),
            ).toExternalForm()

        private val HGAP: CssMetaData<WrappingToolbar, Number> = gapMetaData("-fx-hgap", 0.0) { it.hgapProperty() }
        private val VGAP: CssMetaData<WrappingToolbar, Number> =
            gapMetaData("-fx-vgap", DEFAULT_VGAP) { it.vgapProperty() }

        private val CSS_META_DATA: List<CssMetaData<out Styleable, *>> =
            Region.getClassCssMetaData() + listOf(HGAP, VGAP)

        /** Sets [node]'s margin in its toolbar, as `HBox.setMargin` does; `null` removes it. */
        @JvmStatic
        fun setMargin(
            node: Node,
            insets: Insets?,
        ) {
            if (insets == null) node.properties.remove(MARGIN) else node.properties[MARGIN] = insets
            (node.parent as? WrappingToolbar)?.requestLayout()
        }

        /** [node]'s margin in its toolbar, `null` if none was set. */
        @JvmStatic
        fun getMargin(node: Node): Insets? = node.properties[MARGIN] as? Insets

        /**
         * The CSS properties of the toolbar: those of a [Region], `-fx-hgap` and `-fx-vgap`. Not `@JvmStatic`: it would
         * clash with `Region.getClassCssMetaData()` on the JVM.
         */
        fun getClassCssMetaData(): List<CssMetaData<out Styleable, *>> = CSS_META_DATA

        private fun gapMetaData(
            property: String,
            default: Double,
            styleable: (WrappingToolbar) -> StyleableDoubleProperty,
        ): CssMetaData<WrappingToolbar, Number> =
            object : CssMetaData<WrappingToolbar, Number>(property, StyleConverter.getSizeConverter(), default) {
                override fun isSettable(toolbar: WrappingToolbar) = !styleable(toolbar).isBound

                override fun getStyleableProperty(toolbar: WrappingToolbar): StyleableProperty<Number> =
                    styleable(toolbar)
            }
    }
}
