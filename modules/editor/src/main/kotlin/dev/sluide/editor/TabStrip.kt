package dev.sluide.editor

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
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.scene.shape.Rectangle
import javafx.util.Duration
import java.nio.file.Path
import kotlin.math.abs

private val SELECTED: PseudoClass = PseudoClass.getPseudoClass("selected")

/** How far the mouse has to move with the button down before a tab header is dragged. */
private const val DRAG_THRESHOLD = 5.0

/** How often the row scrolls by one tab while a header is dragged beyond its edge. */
private val AUTO_SCROLL_INTERVAL: Duration = Duration.millis(300.0)

/**
 * The row of tab headers above the editor content. When the headers do not fit, arrow buttons scroll the row
 * one tab at a time and a dropdown lists the tabs that are not completely visible. Dragging a header moves its tab;
 * dragging it beyond the edge of a row that does not fit scrolls the row.
 */
internal class TabStrip(
    private val editorTabs: EditorTabs,
    /** Asked before tabs are closed, e.g. about unsaved changes or running programs; `true` closes them. */
    private val confirmClose: (tabs: List<EditorTab>) -> Boolean = { true },
) : Region() {
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
        children.addAll(viewport, scrollLeftButton, scrollRightButton, overflowMenu)
        editorTabs.tabs.subscribe(::rebuildHeaders)
        editorTabs.selected.subscribe { selected ->
            headers.forEach { it.pseudoClassStateChanged(SELECTED, it.tab === selected) }
            revealSelected = true
            requestLayout()
        }
    }

    /** Tabs that are cut off or scrolled out of view, in tab order. */
    fun hiddenTabs(): List<EditorTab> = overflow.hidden(first).mapNotNull { headers.getOrNull(it)?.tab }

    /** Tabs that are completely visible, in tab order. */
    fun visibleTabs(): List<EditorTab> = overflow.visible(first).mapNotNull { headers.getOrNull(it)?.tab }

    private fun rebuildHeaders() {
        val old = headers.associateBy { it.tab }
        headers = editorTabs.tabs.map { old[it] ?: TabHeader(it) }
        // The dragged tab was closed meanwhile, e.g. because its file was deleted.
        if (drag?.header?.let { it !in headers } == true) endDrag()
        headers.forEach { it.pseudoClassStateChanged(SELECTED, it.tab === editorTabs.selected.get()) }
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
            setOnAction { editorTabs.select(tab) }
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
            val selected = headers.indexOfFirst { it.tab === editorTabs.selected.get() }
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
        if (index != editorTabs.tabs.indexOf(drag.header.tab)) editorTabs.move(drag.header.tab, index)
        requestLayout()
    }

    private fun startDrag(drag: Drag) {
        this.drag = drag
        drag.header.styleClass += "dragging"
        drag.header.viewOrder = -1.0
    }

    private fun dragTo(sceneX: Double) {
        val drag = drag ?: return
        drag.pointer = viewport.sceneToLocal(sceneX, 0.0).x
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

    /**
     * Shows the history of a tab's file, e.g. in the git log area (ADR 0046); `null` leaves Show History out of the
     * tabs' context menus, which have it only for tabs showing a file on disk.
     */
    var showHistory: ((Path) -> Unit)? = null

    /** Whether Show History is enabled for a tab's file; asked when its context menu opens. */
    var canShowHistory: (Path) -> Boolean = { false }

    /** The context menu of the header of [tab]. */
    fun contextMenuOf(tab: EditorTab): ContextMenu = headers.single { it.tab === tab }.menu

    /** The tooltip of the header of [tab]. */
    fun tooltipOf(tab: EditorTab): Tooltip = headers.single { it.tab === tab }.tooltip

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

        /** Where the primary button was pressed, in scene and header x, while it may still start a drag. */
        private var press: Pair<Double, Double>? = null

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
            children.addAll(title, close)
            tooltip = Tooltip(tab.tooltip)
            Tooltip.install(this, tooltip)
            // A file tab follows its file when it is renamed.
            if (tab is OpenFile) tab.pathProperty.subscribe { _ -> tooltip.text = tab.tooltip }
            // Pressing the close button must not select the tab first. Consuming the press on the button instead
            // would keep the button from arming, so it would never fire.
            setOnMousePressed { event ->
                if (event.button == MouseButton.PRIMARY && !close.contains(event.target)) {
                    editorTabs.select(tab)
                    press = event.sceneX to event.x
                }
            }
            setOnMouseDragged { event ->
                val (pressX, grab) = press ?: return@setOnMouseDragged
                if (drag == null && abs(event.sceneX - pressX) > DRAG_THRESHOLD) startDrag(Drag(this, grab))
                if (drag?.header === this) dragTo(event.sceneX)
            }
            setOnMouseReleased { event ->
                if (event.button != MouseButton.PRIMARY) return@setOnMouseReleased
                press = null
                if (drag?.header === this) endDrag()
            }
            setOnMouseClicked { if (it.button == MouseButton.MIDDLE) close(listOf(tab)) { editorTabs.close(tab) } }
            setOnContextMenuRequested { event ->
                menu.show(this, event.screenX, event.screenY)
                event.consume()
            }
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
            val file = tab as? OpenFile
            val history = item("Show History") { file?.let { showHistory?.invoke(it.path) } }
            val historyItems = listOf(SeparatorMenuItem(), history)
            return ContextMenu(
                item("Close") { close(listOf(tab)) { editorTabs.close(tab) } },
                SeparatorMenuItem(),
                closeOthers,
                closeLeft,
                closeRight,
                SeparatorMenuItem(),
                item("Close All") { close(editorTabs.tabs.toList()) { editorTabs.closeAll() } },
            ).apply {
                styleClass += "tab-context-menu"
                setOnShowing {
                    val index = editorTabs.tabs.indexOf(tab)
                    closeOthers.isDisable = editorTabs.tabs.size <= 1
                    closeLeft.isDisable = index <= 0
                    closeRight.isDisable = index >= editorTabs.tabs.lastIndex
                    // Only for files, and only while the window offers it.
                    items.removeAll(historyItems)
                    if (file != null && showHistory != null) {
                        items.addAll(historyItems)
                        history.isDisable = !canShowHistory(file.path)
                    }
                }
            }
        }
    }
}
