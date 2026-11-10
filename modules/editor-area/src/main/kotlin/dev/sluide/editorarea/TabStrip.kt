package dev.sluide.editorarea

import dev.sluide.fileviewers.ViewActivity
import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.css.PseudoClass
import javafx.event.EventTarget
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.scene.shape.Circle
import javafx.scene.shape.Rectangle
import javafx.util.Duration
import javafx.util.Subscription
import kotlin.math.abs

private val SELECTED: PseudoClass = PseudoClass.getPseudoClass("selected")

/** The style class of the selected header of the side that is not current while split. */
private const val MUTED = "editor-tab-muted"

/** How far the mouse has to move with the button down before a tab header is dragged. */
private const val DRAG_THRESHOLD = 5.0

/** The width of the insertion marker of [TabStrip.showInsertMarker]. */
private const val MARKER_WIDTH = 2.0

/** How often the row scrolls by one tab while a header is dragged beyond its edge. */
private val AUTO_SCROLL_INTERVAL: Duration = Duration.millis(300.0)

/** The style class of the marker a tab header shows for its view's [ViewActivity]. */
private const val ACTIVITY_MARKER = "tab-activity"

/** The style class of the icon a tab header shows before its title. */
internal const val TAB_ICON_STYLE_CLASS = "tab-icon"

private const val ACTIVITY_DOT_RADIUS = 3.5

/**
 * The row of tab headers of one [side] above the editor content. When the headers do not fit, arrow buttons scroll the
 * row one tab at a time and a dropdown lists the tabs that are not completely visible. Dragging a header moves its
 * tab; dragging it beyond the edge of a row that does not fit scrolls the row.
 */
internal class TabStrip(
    private val editorTabs: EditorTabs,
    /** The side whose tabs the row shows. */
    private val side: Side,
    /** Asked before tabs are closed, e.g. about unsaved changes or running programs; `true` closes them. */
    private val confirmClose: (tabs: List<EditorTab>) -> Boolean = { true },
    /** Selects a tab the user chose, by a click on its header or in the overflow menu; the editor area focuses it. */
    private val choose: (EditorTab) -> Unit = editorTabs::select,
    /** Moves a tab to a side at an index of that side's row, e.g. by the context menu; the area focuses it. */
    private val moveTo: (EditorTab, Side, Int) -> Unit = { tab, to, index -> editorTabs.moveTo(tab, to, index) },
) : Region() {
    /** Where a dragged header can be dropped besides its own row: the other side's row or the right half of the area. */
    internal var drops: TabDropTargets = NoDropTargets

    private val viewport = Pane().apply { styleClass += "tab-viewport" }
    private val viewportClip = Rectangle()

    val scrollLeftButton = arrowButton("‹", "scroll-left") { scrollBy(-1) }
    val scrollRightButton = arrowButton("›", "scroll-right") { scrollBy(1) }
    val overflowMenu =
        MenuButton().apply {
            styleClass += "overflow-menu"
            isFocusTraversable = false
            setOnShowing { items.setAll(hiddenTabs().map(::menuItem)) }
        }

    /** Shows where a header dragged from another strip would be inserted (the drop position), see [showInsertMarker]. */
    private val insertMarker =
        Region().apply {
            styleClass += "tab-insert-marker"
            isManaged = false
            isMouseTransparent = true
            isVisible = false
        }

    private var headers: List<TabHeader> = emptyList()
    private var first = 0
    private var revealSelected = false
    private var overflow = TabOverflow(emptyList(), 0.0)

    /** The header being dragged, if any. */
    private var drag: Drag? = null
    private var autoScrollDirection = 0
    private val autoScroll =
        Timeline(KeyFrame(AUTO_SCROLL_INTERVAL, { scrollDuringDrag() })).apply { cycleCount = Animation.INDEFINITE }

    init {
        styleClass += "tab-strip"
        viewport.clip = viewportClip
        children.addAll(viewport, scrollLeftButton, scrollRightButton, overflowMenu, insertMarker)
        editorTabs.tabsOf(side).subscribe(::rebuildHeaders)
        editorTabs.selectedOf(side).subscribe { _ ->
            updateHeaderStates()
            revealSelected = true
            requestLayout()
        }
        editorTabs.current.subscribe { _ -> updateHeaderStates() }
        editorTabs.split.subscribe { _ -> updateHeaderStates() }
    }

    private fun isInactive() = editorTabs.split.get() && editorTabs.current.get() != side

    /**
     * Marks the header of the side's selected tab: `:selected` on the current side (or without a split), the style
     * class [MUTED] on the other one. A style class, not a descendant selector, so that its dark rule applies only in
     * the dark theme (ADR 0009).
     */
    private fun updateHeaderStates() {
        val selected = editorTabs.selectedOf(side).get()
        val inactive = isInactive()
        headers.forEach { header ->
            val isSelected = header.tab === selected
            header.pseudoClassStateChanged(SELECTED, isSelected && !inactive)
            if (isSelected && inactive) {
                if (MUTED !in header.styleClass) header.styleClass += MUTED
            } else {
                header.styleClass -= MUTED
            }
        }
    }

    /** Tabs that are cut off or scrolled out of view, in tab order. */
    fun hiddenTabs(): List<EditorTab> = overflow.hidden(first).mapNotNull { headers.getOrNull(it)?.tab }

    /** Tabs that are completely visible, in tab order. */
    fun visibleTabs(): List<EditorTab> = overflow.visible(first).mapNotNull { headers.getOrNull(it)?.tab }

    /** Whether the scene point is over this strip. */
    fun rowContains(
        sceneX: Double,
        sceneY: Double,
    ): Boolean = sceneToLocal(sceneX, sceneY)?.let { layoutBounds.contains(it) } == true

    /** The index of this row at which a header dropped at [sceneX] is inserted, at the row's current scroll position. */
    fun insertIndexAt(sceneX: Double): Int = overflow.insertIndex(first, viewport.sceneToLocal(sceneX, 0.0).x)

    /** Shows the insertion marker at the gap before the tab at [index], or hides it for `null`. */
    fun showInsertMarker(index: Int?) {
        insertMarker.isVisible = index != null
        if (index == null) return
        val x = (overflow.offset(index) - overflow.offset(first)).coerceIn(0.0, viewport.width)
        insertMarker.resizeRelocate(
            viewport.layoutX + x - MARKER_WIDTH / 2,
            viewport.layoutY,
            MARKER_WIDTH,
            viewport.height,
        )
    }

    private fun rebuildHeaders() {
        val old = headers.associateBy { it.tab }
        headers = editorTabs.tabsOf(side).map { old[it] ?: TabHeader(it) }
        (old.values - headers.toSet()).forEach { it.dispose() }
        // The dragged tab was closed meanwhile, e.g. because its file was deleted.
        if (drag?.header?.let { it !in headers } == true) endDrag()
        updateHeaderStates()
        // Only when tabs were opened or closed: a moved tab keeps its header in the scene, so a drag in progress keeps
        // getting the mouse events. The order of the children does not matter, layoutChildren places them.
        if (viewport.children.size != headers.size || !viewport.children.containsAll(headers)) {
            viewport.children.setAll(headers)
        }
        revealSelected = true
        requestLayout()
    }

    private fun scrollBy(tabs: Int) {
        first = overflow.clamp(first + tabs)
        requestLayout()
    }

    private fun menuItem(tab: EditorTab) =
        MenuItem(tab.title.value).apply {
            graphic = tab.icon()
            setOnAction { choose(tab) }
        }

    override fun computePrefHeight(width: Double): Double {
        val controls = listOf<Region>(scrollLeftButton, scrollRightButton, overflowMenu) + headers
        return insets.top + controls.maxOf { it.prefHeight(-1.0) } + insets.bottom
    }

    override fun computePrefWidth(height: Double): Double =
        insets.left + headers.sumOf { it.prefWidth(-1.0) } + insets.right

    override fun computeMinWidth(height: Double): Double = insets.left + insets.right

    override fun layoutChildren() {
        val top = insets.top
        val height = this.height - top - insets.bottom
        var left = insets.left
        var right = width - insets.right
        val widths = headers.map { snapSizeX(it.prefWidth(-1.0)) }

        val overflows = TabOverflow(widths, right - left).overflows
        for (control in listOf(scrollLeftButton, scrollRightButton, overflowMenu)) {
            control.isVisible = overflows
        }
        if (overflows) {
            val leftWidth = snapSizeX(scrollLeftButton.prefWidth(-1.0))
            scrollLeftButton.resizeRelocate(left, top, leftWidth, height)
            left += leftWidth
            val menuWidth = snapSizeX(overflowMenu.prefWidth(-1.0))
            right -= menuWidth
            overflowMenu.resizeRelocate(right, top, menuWidth, height)
            val rightWidth = snapSizeX(scrollRightButton.prefWidth(-1.0))
            right -= rightWidth
            scrollRightButton.resizeRelocate(right, top, rightWidth, height)
        }

        val viewportWidth = (right - left).coerceAtLeast(0.0)
        overflow = TabOverflow(widths, viewportWidth)
        first = overflow.clamp(first)
        // While dragging, the row scrolls only beyond its edges; the dropped tab is revealed when the drag ends.
        if (revealSelected && drag == null) {
            revealSelected = false
            val selected = headers.indexOfFirst { it.tab === editorTabs.selectedOf(side).get() }
            if (selected >= 0) first = overflow.reveal(selected, first)
        }
        scrollLeftButton.isDisable = first == 0
        scrollRightButton.isDisable = first >= overflow.maxFirst

        viewport.resizeRelocate(left, top, viewportWidth, height)
        viewportClip.width = viewportWidth
        viewportClip.height = height
        val offset = overflow.offset(first)
        var x = -offset
        headers.forEachIndexed { index, header ->
            val left = if (header === drag?.header) dragLeft(widths, viewportWidth) - offset else x
            header.resizeRelocate(left, 0.0, widths[index], height)
            x += widths[index]
        }
    }

    /**
     * A header being dragged: grabbed [grab] from its left edge, with the pointer at [pointer] in viewport
     * coordinates.
     */
    private class Drag(
        val header: TabHeader,
        val grab: Double,
    ) {
        var pointer = 0.0
    }

    private fun headerWidths(): List<Double> = headers.map { snapSizeX(it.prefWidth(-1.0)) }

    /**
     * Where the dragged header's left edge is, measured like [TabOverflow.offset]: under the pointer where it was
     * grabbed, but inside the viewport and not beyond the last tab.
     */
    private fun dragLeft(
        widths: List<Double>,
        viewportWidth: Double,
    ): Double {
        val drag = drag ?: return 0.0
        val offset = TabOverflow(widths, viewportWidth).offset(first)
        val width = widths[headers.indexOf(drag.header)]
        val max = minOf(offset + viewportWidth, widths.sum()) - width
        return (offset + drag.pointer - drag.grab).coerceAtMost(max).coerceAtLeast(offset)
    }

    /** Moves the dragged tab to where its header is now. */
    private fun followDrag() {
        val drag = drag ?: return
        val widths = headerWidths()
        val overflow = TabOverflow(widths, viewport.width)
        val index = overflow.dropIndex(headers.indexOf(drag.header), dragLeft(widths, viewport.width))
        if (index != editorTabs.tabsOf(side).indexOf(drag.header.tab)) editorTabs.move(drag.header.tab, index)
        requestLayout()
    }

    private fun startDrag(drag: Drag) {
        this.drag = drag
        drag.header.styleClass += "dragging"
        drag.header.viewOrder = -1.0
    }

    private fun dragTo(
        sceneX: Double,
        sceneY: Double,
    ) {
        val drag = drag ?: return
        drag.pointer = viewport.sceneToLocal(sceneX, 0.0).x
        if (drops.hover(drag.header.tab, side, sceneX, sceneY)) {
            // Over another row or zone: this row keeps its order and stops scrolling; the header stays clamped.
            autoScroll.stop()
            autoScrollDirection = 0
            requestLayout()
            return
        }
        followDrag()
        val direction =
            when {
                !overflow.overflows -> 0
                drag.pointer < 0 -> -1
                drag.pointer > viewport.width -> 1
                else -> 0
            }
        if (direction == autoScrollDirection) return
        autoScrollDirection = direction
        autoScroll.stop()
        if (direction != 0) {
            scrollDuringDrag()
            autoScroll.play()
        }
    }

    private fun scrollDuringDrag() {
        scrollBy(autoScrollDirection)
        // Scrolling has to show on the header's position before it decides where the tab goes.
        layout()
        followDrag()
    }

    private fun endDrag() {
        val drag = drag ?: return
        this.drag = null
        drops.clear()
        autoScroll.stop()
        autoScrollDirection = 0
        drag.header.styleClass -= "dragging"
        drag.header.viewOrder = 0.0
        revealSelected = true
        requestLayout()
    }

    private fun arrowButton(
        text: String,
        styleClass: String,
        action: () -> Unit,
    ) = Button(text).apply {
        this.styleClass += styleClass
        isFocusTraversable = false
        setOnAction { action() }
    }

    /** Runs [action], which closes [tabs], unless closing them is not confirmed ([confirmClose]). */
    private fun close(
        tabs: List<EditorTab>,
        action: () -> Unit,
    ) {
        if (confirmClose(tabs)) action()
    }

    private fun Node.contains(target: EventTarget): Boolean =
        generateSequence(target as? Node) { it.parent }.any { it === this }

    /** What the window offers in the context menus of the tabs. */
    val menuHooks = TabMenuHooks()

    /** The context menu of the header of [tab]. */
    fun contextMenuOf(tab: EditorTab): ContextMenu = headers.single { it.tab === tab }.menu

    /** The tooltip of the header of [tab]. */
    fun tooltipOf(tab: EditorTab): Tooltip = headers.single { it.tab === tab }.tooltip

    private class Press(
        /** Where the primary button was pressed, in scene coordinates. */
        val sceneX: Double,
        val sceneY: Double,
        /** The pressed point's x in the header. */
        val grab: Double,
    )

    /**
     * One tab: its title (with a tooltip) and a close button. Clicking it selects the tab, dragging it moves the tab,
     * right-clicking opens a menu with close actions.
     */
    private inner class TabHeader(
        val tab: EditorTab,
    ) : HBox() {
        val menu: ContextMenu by lazy(::createMenu)

        /** The tooltip of the header, showing [EditorTab.tooltip]. */
        val tooltip: Tooltip

        /**
         * What the header follows of its tab; ended by [dispose]. The activity may be a constant shared by all views,
         * which would keep the header, and its tab's views, alive.
         */
        private val subscriptions = mutableListOf<Subscription>()

        /** Where the primary button was pressed, while it may still start a drag. */
        private var press: Press? = null

        init {
            styleClass += "editor-tab"
            // Positioned and sized by TabStrip.layoutChildren; the viewport Pane must not resize it to its pref size.
            isManaged = false
            val close =
                Button("×").apply {
                    styleClass += "close-button"
                    isFocusTraversable = false
                    setOnAction { close(listOf(tab)) { editorTabs.close(tab) } }
                }
            val title = Label().apply { textProperty().bind(tab.title) }
            // Headers are unmanaged, so a longer or shorter title does not lay out the strip again by itself.
            title.textProperty().subscribe { _ -> this@TabStrip.requestLayout() }
            val icon = tab.icon()?.apply { styleClass += TAB_ICON_STYLE_CLASS }
            if (icon != null) children.add(icon)
            children.addAll(title, close)
            tooltip = Tooltip(tab.tooltip)
            Tooltip.install(this, tooltip)
            if (tab is OpenFile) {
                // A file tab follows its file when it is renamed.
                subscriptions += tab.pathProperty.subscribe { _ -> tooltip.text = tab.tooltip }
            }
            subscriptions += tab.activity.subscribe(::showActivity)
            if (tab is OpenFile) {
                subscriptions +=
                    bindTabLock(this, title, tab.editor.readOnlyMarked, { tab.editor.lockMarkerText }) {
                        this@TabStrip.requestLayout()
                    }
            }
            // Pressing the close button must not select the tab first. Consuming the press on the button instead
            // would keep the button from arming, so it would never fire.
            setOnMousePressed { event ->
                if (event.button == MouseButton.PRIMARY && !close.contains(event.target)) {
                    choose(tab)
                    press = Press(event.sceneX, event.sceneY, event.x)
                }
            }
            setOnMouseDragged { event ->
                val press = press ?: return@setOnMouseDragged
                // A header that was closed meanwhile still gets the gesture, but must not start a drag.
                if (this !in headers) return@setOnMouseDragged
                val moved =
                    abs(event.sceneX - press.sceneX) > DRAG_THRESHOLD ||
                        abs(event.sceneY - press.sceneY) > DRAG_THRESHOLD
                if (drag == null && moved) startDrag(Drag(this, press.grab))
                if (drag?.header === this) dragTo(event.sceneX, event.sceneY)
            }
            setOnMouseReleased { event ->
                if (event.button != MouseButton.PRIMARY) return@setOnMouseReleased
                press = null
                if (drag?.header === this) {
                    endDrag()
                    drops.drop(tab, side, event.sceneX, event.sceneY)
                }
            }
            setOnMouseClicked { if (it.button == MouseButton.MIDDLE) close(listOf(tab)) { editorTabs.close(tab) } }
            setOnContextMenuRequested { event ->
                menu.show(this, event.screenX, event.screenY)
                event.consume()
            }
        }

        /** Stops following the tab, once the header is no longer shown. */
        fun dispose() {
            press = null
            subscriptions.forEach { it.unsubscribe() }
            subscriptions.clear()
        }

        /** Shows [activity] as a marker before the title: a spinner while running, a red dot after a failure. */
        private fun showActivity(activity: ViewActivity) {
            children.removeIf { it.styleClass.contains(ACTIVITY_MARKER) }
            val marker =
                when (activity) {
                    ViewActivity.NONE -> null
                    ViewActivity.RUNNING -> ProgressIndicator().apply { styleClass += "tab-activity-running" }
                    ViewActivity.FAILED -> Circle(ACTIVITY_DOT_RADIUS).apply { styleClass += "tab-activity-failed" }
                }
            if (marker != null) children.add(0, marker.apply { styleClass += ACTIVITY_MARKER })
            this@TabStrip.requestLayout()
        }

        private fun createMenu(): ContextMenu {
            fun item(
                text: String,
                action: () -> Unit,
            ) = MenuItem(text).apply { setOnAction { action() } }
            val closeOthers = item("Close Others") { close(editorTabs.othersOf(tab)) { editorTabs.closeOthers(tab) } }
            val closeLeft =
                item("Close Tabs to the Left") { close(editorTabs.leftOf(tab)) { editorTabs.closeLeftOf(tab) } }
            val closeRight =
                item("Close Tabs to the Right") { close(editorTabs.rightOf(tab)) { editorTabs.closeRightOf(tab) } }
            val moveRight =
                item("Move to Right Side") { moveTo(tab, Side.RIGHT, editorTabs.tabsOf(Side.RIGHT).size) }
            val moveLeft =
                item("Move to Left Side") { moveTo(tab, Side.LEFT, editorTabs.tabsOf(Side.LEFT).size) }
            val file = tab as? OpenFile
            val history = item("Show History") { file?.let { menuHooks.showHistory?.invoke(it.path) } }
            val select = item("Select in Explorer") { file?.let { menuHooks.selectInExplorer?.invoke(it.path) } }
            val fileItems = listOf(SeparatorMenuItem(), history, select)
            // The view's own items with their separator, read again on each showing.
            var viewItems = emptyList<MenuItem>()
            return ContextMenu(
                item("Close") { close(listOf(tab)) { editorTabs.close(tab) } },
                SeparatorMenuItem(),
                closeOthers,
                closeLeft,
                closeRight,
                SeparatorMenuItem(),
                item("Close All") { close(editorTabs.tabsOf(side).toList()) { editorTabs.closeAll(side) } },
            ).apply {
                styleClass += "tab-context-menu"
                setOnShowing {
                    items.removeAll(listOf(moveRight, moveLeft))
                    items.add(1, if (side == Side.LEFT) moveRight else moveLeft)
                    moveRight.isDisable = !editorTabs.split.get() && editorTabs.tabsOf(Side.LEFT).size <= 1
                    val sideTabs = editorTabs.tabsOf(side)
                    val index = sideTabs.indexOf(tab)
                    closeOthers.isDisable = sideTabs.size <= 1
                    closeLeft.isDisable = index <= 0
                    closeRight.isDisable = index >= sideTabs.lastIndex
                    // Only for files, and only while the window offers it.
                    items.removeAll(fileItems)
                    items.removeAll(viewItems)
                    viewItems = emptyList()
                    if (file != null) {
                        val offered =
                            listOfNotNull(
                                history.takeIf { menuHooks.showHistory != null },
                                select.takeIf { menuHooks.selectInExplorer != null },
                            )
                        if (offered.isNotEmpty()) items.addAll(listOf(fileItems.first()) + offered)
                        history.isDisable = !menuHooks.canShowHistory(file.path)
                        select.isDisable = !menuHooks.canSelectInExplorer(file.path)
                        val own = file.editor.tabMenuItems
                        if (own.isNotEmpty()) {
                            viewItems = listOf(SeparatorMenuItem()) + own
                            items.addAll(viewItems)
                        }
                    }
                }
            }
        }
    }
}
