package dev.sluide.editorarea

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.SplitPane
import javafx.scene.control.skin.SplitPaneSkin
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.shape.Rectangle

/**
 * One side of the editor area: its [strip] above the content of the side's selected tab. A mouse press in it, or the
 * focus moving into it, makes [side] the current side of [editorTabs]. [focusShown] focuses a tab that is shown in
 * place of one that was closed or replaced while the side had the focus (ADR 0050); a tab shown because the one before
 * moved to the other side does not take the focus, the moved tab keeps it.
 */
internal class SidePane(
    val side: Side,
    val strip: TabStrip,
    editorTabs: EditorTabs,
    focusShown: (EditorTab) -> Unit,
) : BorderPane() {
    private val content =
        StackPane().apply {
            styleClass += "editor-content"
            // Content wider than its side starts at the side's left edge and is cut off at the right one.
            alignment = Pos.CENTER_LEFT
            BorderPane.setAlignment(this, Pos.TOP_LEFT)
        }

    /** The tab shown before, to tell a tab that left the area from one that moved to the other side. */
    private var previous: EditorTab? = null

    init {
        top = strip
        center = content
        // Always clipped: while split, a side may be narrower than its content's minimum width (a wide toolbar), which
        // is then cut off at the side's edge instead of being drawn over the other side.
        clip =
            Rectangle().also {
                it.widthProperty().bind(widthProperty())
                it.heightProperty().bind(heightProperty())
            }
        addEventFilter(MouseEvent.MOUSE_PRESSED) { editorTabs.makeCurrent(side) }
        focusWithinProperty().subscribe { within -> if (within) editorTabs.makeCurrent(side) }
        editorTabs.selectedOf(side).subscribe { selected ->
            // Asked before the content changes: until then, a focus owner in the old content is still in the scene.
            val owner = scene?.focusOwner
            val focusHere = owner == null || owner.scene == null || owner.isInside(this@SidePane)
            val replaced = previous?.let { it in editorTabs.tabs } != true
            previous = selected
            content.children.setAll(listOfNotNull(selected?.content))
            // A tab shown in place of another (closed, replaced) takes the focus only if the side had it, e.g. not
            // after its file was deleted from the file tree (ADR 0050). Opening and choosing a tab always focus it.
            if (selected != null && replaced && focusHere && (selected is OpenFile || selected.focusOnShow)) {
                focusShown(selected)
            }
        }
    }
}

/**
 * The sides of the editor area in one horizontal [SplitPane]: the left [SidePane] alone, or, while
 * [EditorTabs.split], with the right one next to it and the divider in the middle each time the right side opens.
 * The split pane is skinned at once, so a side that is added is in the scene graph and takes focus requests right away.
 * [focusTab] focuses the left side's selected tab when the right side disappears with the focus.
 */
internal class EditorSides(
    private val editorTabs: EditorTabs,
    private val left: SidePane,
    private val right: SidePane,
    /** Moves a tab to a side at an index and focuses it, for a drop. */
    private val moveTo: (EditorTab, Side, Int) -> Unit,
    focusTab: (EditorTab) -> Unit,
) : StackPane(),
    TabDropTargets {
    private val splitPane =
        SplitPane().apply {
            styleClass += SINGLE_SIDE
            skin = SplitPaneSkin(this)
            items.add(left)
        }

    /** Covers the right half of the area while a header is dragged over it and the right side can be opened. */
    private val zone =
        Region().apply {
            styleClass += "tab-drop-zone"
            isMouseTransparent = true
            isVisible = false
            maxWidthProperty().bind(this@EditorSides.widthProperty().divide(2))
            setAlignment(this, Pos.TOP_RIGHT)
        }

    /** Where a dragged tab goes: [index] of [side]'s row, or the right side opened by the [zone]. */
    private class Drop(
        val side: Side,
        val index: Int,
        val zone: Boolean,
    )

    init {
        left.strip.drops = this
        right.strip.drops = this
        children.addAll(splitPane, zone)
        editorTabs.split.subscribe { split ->
            if (split) {
                splitPane.styleClass -= SINGLE_SIDE
                // The split pane keeps every side at least as wide as its content needs, which would push the divider
                // away from the middle for a view with a wide toolbar; while split, the sides cut their content off.
                // Some width stays, so a side cannot be dragged out of sight with its tabs.
                left.minWidth = SIDE_MIN_WIDTH
                right.minWidth = SIDE_MIN_WIDTH
                splitPane.items.add(right)
                splitPane.setDividerPositions(0.5)
            } else if (right in splitPane.items) {
                splitPane.items.remove(right)
                left.minWidth = Region.USE_COMPUTED_SIZE
                if (SINGLE_SIDE !in splitPane.styleClass) splitPane.styleClass += SINGLE_SIDE
            }
        }
        // The right side disappears with its selection becoming null, published after the left selection is final.
        // If the focus left with the pane, the left side's selected tab takes it; a moved tab's focus is in the left
        // pane by now, and a collapse has focused its tab already.
        editorTabs.selectedOf(Side.RIGHT).subscribe { selected ->
            if (selected != null) return@subscribe
            val owner = scene?.focusOwner
            if (owner != null && owner.scene != null && !owner.isInside(right)) return@subscribe
            editorTabs.selectedOf(Side.LEFT).get()?.let(focusTab)
        }
    }

    private fun target(
        from: Side,
        sceneX: Double,
        sceneY: Double,
    ): Drop? {
        val split = editorTabs.split.get()
        val other = if (from == Side.LEFT) right else left
        if (split && other.strip.rowContains(sceneX, sceneY)) {
            return Drop(other.side, other.strip.insertIndexAt(sceneX), zone = false)
        }
        if (!split && from == Side.LEFT && editorTabs.tabsOf(Side.LEFT).size >= 2 && inZone(sceneX, sceneY)) {
            return Drop(Side.RIGHT, 0, zone = true)
        }
        return null
    }

    /** Whether the point is below the left strip, in the right half of the left pane. */
    private fun inZone(
        sceneX: Double,
        sceneY: Double,
    ): Boolean {
        val pane = left.localToScene(left.layoutBounds)
        val stripBottom = left.strip.localToScene(left.strip.layoutBounds).maxY
        return sceneY > stripBottom && sceneY <= pane.maxY && sceneX >= pane.minX + pane.width / 2 &&
            sceneX <= pane.maxX
    }

    override fun hover(
        tab: EditorTab,
        from: Side,
        sceneX: Double,
        sceneY: Double,
    ): Boolean {
        val drop = target(from, sceneX, sceneY)
        for (pane in listOf(left, right)) {
            pane.strip.showInsertMarker(drop?.takeIf { !it.zone && it.side == pane.side }?.index)
        }
        val showZone = drop?.zone == true
        if (showZone && !zone.isVisible) setMargin(zone, Insets(left.strip.height, 0.0, 0.0, 0.0))
        zone.isVisible = showZone
        return drop != null
    }

    override fun drop(
        tab: EditorTab,
        from: Side,
        sceneX: Double,
        sceneY: Double,
    ): Boolean {
        val drop = target(from, sceneX, sceneY)
        clear()
        if (drop == null) return false
        moveTo(tab, drop.side, drop.index)
        return true
    }

    override fun clear() {
        zone.isVisible = false
        left.strip.showInsertMarker(null)
        right.strip.showInsertMarker(null)
    }

    private companion object {
        /** Makes the split pane of a single side look like no split pane at all. */
        const val SINGLE_SIDE = "single-side"

        /** The width the divider leaves each side while split. */
        const val SIDE_MIN_WIDTH = 120.0
    }
}
