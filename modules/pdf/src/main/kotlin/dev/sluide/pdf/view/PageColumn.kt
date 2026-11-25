package dev.sluide.pdf.view

import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.engine.PageSize
import dev.sluide.pdf.engine.PageTarget
import javafx.beans.property.ReadOnlyIntegerProperty
import javafx.beans.property.ReadOnlyIntegerWrapper
import javafx.geometry.BoundingBox
import javafx.geometry.Bounds
import javafx.geometry.Orientation
import javafx.scene.Cursor
import javafx.scene.Scene
import javafx.scene.control.ContextMenu
import javafx.scene.control.ScrollBar
import javafx.scene.control.ScrollPane
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.input.ZoomEvent
import javafx.scene.layout.Pane
import javafx.stage.Window
import kotlin.math.max
import kotlin.math.pow

/**
 * The pages of a PDF view (ADR 0192): one column of [PageSlot]s in a scroll pane ([node], the focusable `pages` hook),
 * laid out by [PageLayout] at the [zoom]'s scale, rendered lazily by [PageImages] at that scale times the window's
 * output scale. Only the pages around the viewport have a slot. Scroll keys, Shortcut+wheel and pinch work on the
 * column; [userScrolled] hears scrolls by the user (wheel, keys, scroll bar), not those of [scrollTo], a zoom or a
 * resize. The only class of the view that knows pixels. FX thread only.
 */
internal class PageColumn(
    renderWorker: PdfWorker,
) {
    val zoom = ZoomModel()
    private val content = Pane().apply { styleClass += "pdf-column" }
    val node: ScrollPane =
        ScrollPane(content).apply {
            styleClass += "pdf-pages"
            isFocusTraversable = true
            // The column is as wide as the viewport, or as its pages if they are wider (its min width).
            isFitToWidth = true
        }
    private val images = PageImages(renderWorker, convert = ::toImage, pixels = ::pixelsOf, changed = ::showImage)
    val overlays = PageOverlays(::drawOverlays)
    private val slots = HashMap<Int, PageSlot>()
    private var sizes: List<PageSize> = emptyList()
    private var layout: PageLayout? = null
    private val current = ReadOnlyIntegerWrapper(0)
    val currentPage: ReadOnlyIntegerProperty = current.readOnlyProperty
    val userScrolled = mutableListOf<() -> Unit>()

    /** Called after every zoom, with the scale or fit mode changed. */
    val zoomed = mutableListOf<() -> Unit>()
    val contextMenu = ContextMenu()

    /** The viewport's position in the column, kept across resizes. */
    private var top = 0.0
    private var left = 0.0
    private var appScroll = false

    /** The viewport's size when [resized] last handled it. */
    private var viewportSize = 0.0 to 0.0

    /** Where to scroll once the viewport has a size: to a target, or (`null` in it) to the top of the column. */
    private var pending: Pending? = null

    private class Pending(
        val target: PageTarget?,
    )

    private val outputScale =
        node
            .sceneProperty()
            .flatMap(Scene::windowProperty)
            .flatMap(Window::outputScaleXProperty)
            .map { it.toDouble() }
            .orElse(1.0)

    var cursor: Cursor?
        get() = content.cursor
        set(value) {
            content.cursor = value
        }

    init {
        node.vvalueProperty().addListener { _, _, _ -> scrolled() }
        node.hvalueProperty().addListener { _, _, _ -> scrolled() }
        node.viewportBoundsProperty().subscribe { _ -> resized() }
        outputScale.subscribe { _ -> update() }
        node.addEventFilter(KeyEvent.KEY_PRESSED, ::onKey)
        node.addEventFilter(ScrollEvent.SCROLL) { event ->
            if (event.isShortcutDown) {
                if (event.deltaY != 0.0) zoomAt(WHEEL_STEP.pow(event.deltaY / WHEEL_UNIT), event.sceneX, event.sceneY)
                event.consume()
            }
        }
        node.addEventFilter(ZoomEvent.ZOOM) { event ->
            zoomAt(event.zoomFactor, event.sceneX, event.sceneY)
            event.consume()
        }
        node.addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED) { event ->
            // JavaFX shows no context menu without items, so it opens only once another feature filled it.
            if (pagePointAt(event.sceneX, event.sceneY) != null) {
                contextMenu.show(node, event.screenX, event.screenY)
                event.consume()
            }
        }
    }

    /**
     * Shows pages of [pageSizes] in place of those shown, at the zoom in effect (re-fitted in a fit mode), scrolled to
     * [at], or to the top of the column (the margin above the first page in view) if `null`; blank until rendered.
     */
    fun show(
        pageSizes: List<PageSize>,
        at: PageTarget?,
    ) {
        clear()
        sizes = pageSizes
        layout = PageLayout(pageSizes, zoom.scale)
        pending = Pending(at)
        resized()
    }

    /** Shows no pages; cancels the renders and releases the images. */
    fun clear() {
        images.clear()
        slots.clear()
        content.children.clear()
        sizes = emptyList()
        layout = null
        pending = null
    }

    /** Scrolls [target] to the top of the viewport (an app scroll: [userScrolled] is not called). */
    fun scrollTo(target: PageTarget) {
        val shown = layout
        if (shown == null || !viewportKnown()) {
            pending = Pending(target)
            return
        }
        scrollToPixels(left, shown.topOf(target))
    }

    /** Scrolls the least to show [rect] of [page], centring it vertically if it is off-screen (an app scroll). */
    fun scrollIntoView(
        page: Int,
        rect: PageRect,
    ) {
        val bounds = contentRect(page, rect) ?: return
        val view = viewport()
        scrollToPixels(
            revealed(bounds.minX, bounds.width, view.left, view.width, centre = false),
            revealed(bounds.minY, bounds.height, view.top, view.height, centre = true),
        )
    }

    /** The point of a page under the scene point, in points; `null` outside the pages. */
    fun pagePointAt(
        sceneX: Double,
        sceneY: Double,
    ): PagePoint? {
        val shown = layout ?: return null
        val point = content.sceneToLocal(sceneX, sceneY) ?: return null
        val page = shown.pageAt(point.y) ?: return null
        val x = (point.x - shown.left(page, columnWidth())) / shown.scale
        if (x < 0 || x > sizes[page].width) return null
        return PagePoint(page, x, (point.y - shown.top(page)) / shown.scale)
    }

    /** [rect] of [page] in scene coordinates; `null` for a page that is not there. */
    fun pageRectToScene(
        page: Int,
        rect: PageRect,
    ): Bounds? = contentRect(page, rect)?.let(content::localToScene)

    fun zoomIn() = zoomWith(null) { it.zoomIn() }

    fun zoomOut() = zoomWith(null) { it.zoomOut() }

    fun actualSize() = zoomWith(null) { it.actualSize() }

    /** Fits the pages in [mode]; Fit Page shows the current page whole. */
    fun fit(mode: FitMode) {
        val page = current.get()
        zoomWith(null) { it.fit(mode, fitTarget()) }
        val shown = layout
        if (mode == FitMode.PAGE && shown != null) scrollToPixels(left, shown.top(page) - PageLayout.MARGIN)
    }

    /** Releases the images and cancels the renders; the column is not used afterwards. */
    fun dispose() {
        slots.values.forEach { it.show(null, false) }
        clear()
        contextMenu.hide()
    }

    /** The slots that exist, by page, for tests. */
    internal val pageSlots: Map<Int, PageSlot> get() = slots

    /** The top of the viewport in the column, for tests. */
    internal val viewTop: Double get() = top

    private fun viewportKnown() = node.viewportBounds.width > 0 && node.viewportBounds.height > 0

    private fun viewport() = Viewport(left, top, node.viewportBounds.width, node.viewportBounds.height)

    /** The column's width as the scroll pane makes it: the viewport's, or the pages' if wider (beyond rounding). */
    private fun columnWidth(): Double = max(node.viewportBounds.width, minColumnWidth())

    private fun minColumnWidth() = (layout?.width ?: 0.0) - ROUNDING

    private fun maxTop() = max(0.0, (layout?.height ?: 0.0) - node.viewportBounds.height)

    private fun maxLeft() = max(0.0, columnWidth() - node.viewportBounds.width)

    private fun contentRect(
        page: Int,
        rect: PageRect,
    ): Bounds? {
        val shown = layout ?: return null
        if (page !in 0 until shown.pageCount) return null
        val scale = shown.scale
        return BoundingBox(
            shown.left(page, columnWidth()) + rect.x * scale,
            shown.top(page) + rect.y * scale,
            rect.width * scale,
            rect.height * scale,
        )
    }

    /** The viewport's size changed: re-fits in a fit mode, keeping the point at its top left, or keeps its position. */
    private fun resized() {
        val old = layout ?: return
        if (!viewportKnown()) return
        // The viewport's bounds move with the scroll position too; only a new size, or pages to show, count here.
        val size = node.viewportBounds.width to node.viewportBounds.height
        if (size == viewportSize && pending == null) return
        viewportSize = size
        val view = viewport()
        if (zoom.refit(fitTarget())) {
            relayout(old, view, 0.0, 0.0)
        } else {
            sizeContent()
            placeSlots()
            scrollToPixels(left, top)
        }
        pending?.let {
            pending = null
            if (it.target != null) scrollTo(it.target) else scrollToPixels(0.0, 0.0)
        }
    }

    private fun zoomAt(
        factor: Double,
        sceneX: Double,
        sceneY: Double,
    ) {
        val point = content.sceneToLocal(sceneX, sceneY) ?: return
        zoomWith(point.x - left to point.y - top) { it.zoomBy(factor) }
    }

    /** Changes the zoom with [change], keeping the point at [anchor] (viewport coordinates; its centre if `null`). */
    private fun zoomWith(
        anchor: Pair<Double, Double>?,
        change: (ZoomModel) -> Unit,
    ) {
        val old = layout ?: return
        if (!viewportKnown()) return
        val view = viewport()
        change(zoom)
        val (x, y) = anchor ?: (view.width / 2 to view.height / 2)
        relayout(old, view, x, y)
    }

    private fun relayout(
        old: PageLayout,
        view: Viewport,
        anchorX: Double,
        anchorY: Double,
    ) {
        val new = PageLayout(sizes, zoom.scale)
        layout = new
        sizeContent()
        placeSlots()
        val kept = new.keepAnchor(old, view, anchorX, anchorY)
        scrollToPixels(kept.left, kept.top)
        zoomed.toList().forEach { it() }
    }

    private fun fitTarget(): FitTarget {
        val insets = node.insets
        val bar =
            node
                .lookupAll(".scroll-bar")
                .filterIsInstance<ScrollBar>()
                .firstOrNull { it.orientation == Orientation.VERTICAL }
        return FitTarget(
            sizes[current.get().coerceIn(sizes.indices)],
            node.width - insets.left - insets.right,
            node.height - insets.top - insets.bottom,
            bar?.let { if (it.width > 0) it.width else it.prefWidth(-1.0) } ?: SCROLL_BAR,
        )
    }

    /**
     * Gives the column the size of the pages at once: the scroll pane keeps the content's offset in pixels when the
     * content changes its size, so a position set before the next layout pass would be kept at the old size's offset.
     */
    private fun sizeContent() {
        val shown = layout ?: return
        content.setMinSize(minColumnWidth(), shown.height)
        content.setPrefSize(minColumnWidth(), shown.height)
        content.setMaxSize(Double.MAX_VALUE, shown.height)
        appScroll = true
        try {
            content.resize(columnWidth(), shown.height)
        } finally {
            appScroll = false
        }
    }

    /** An app scroll to [x], [y] of the column, kept within it. */
    private fun scrollToPixels(
        x: Double,
        y: Double,
    ) {
        top = y.coerceIn(0.0, maxTop())
        left = x.coerceIn(0.0, maxLeft())
        applyScroll()
        update()
    }

    /** Sets the scroll values to the position kept in [top] and [left]. */
    private fun applyScroll() {
        appScroll = true
        try {
            node.vvalue = if (maxTop() > 0) top / maxTop() else 0.0
            node.hvalue = if (maxLeft() > 0) left / maxLeft() else 0.0
        } finally {
            appScroll = false
        }
    }

    /** The scroll pane moved: by the user, unless the view moved it itself. */
    private fun scrolled() {
        if (appScroll) return
        top = node.vvalue * maxTop()
        left = node.hvalue * maxLeft()
        update()
        userScrolled.toList().forEach { it() }
    }

    private fun onKey(event: KeyEvent) {
        if (event.isShortcutDown || event.isAltDown || event.isControlDown || event.isMetaDown) return
        val (dx, dy) = scrollKeyDelta(event.code, event.isShiftDown, node.viewportBounds.height) ?: return
        event.consume()
        // A user scroll, through the scroll pane's values like the wheel's and the scroll bar's.
        if (dy != 0.0 && maxTop() > 0) node.vvalue = ((top + dy) / maxTop()).coerceIn(0.0, 1.0)
        if (dx != 0.0 && maxLeft() > 0) node.hvalue = ((left + dx) / maxLeft()).coerceIn(0.0, 1.0)
    }

    /** Gives the pages around the viewport a slot, drops the other slots, and renders what is missing. */
    private fun update() {
        val shown = layout ?: return
        if (!viewportKnown()) return
        val view = viewport()
        val page = shown.currentPage(view)
        current.set(page)
        val order = renderOrder(shown, view)
        val wanted = order.mapTo(HashSet()) { it.first }
        slots.keys.filter { it !in wanted }.forEach { content.children.remove(slots.remove(it)) }
        for (wantedPage in wanted) {
            if (wantedPage !in slots) {
                val slot = PageSlot(wantedPage)
                slots[wantedPage] = slot
                content.children += slot
                slot.drawOverlays(overlays.on(wantedPage))
                placeSlot(wantedPage, slot)
            }
        }
        images.update(order, shown.scale * outputScale.value, page)
    }

    private fun placeSlots() = slots.forEach(::placeSlot)

    private fun placeSlot(
        page: Int,
        slot: PageSlot,
    ) {
        val shown = layout ?: return
        slot.place(
            shown.left(page, columnWidth()),
            shown.top(page),
            shown.pageWidth(page),
            shown.pageHeight(page),
            shown.scale,
        )
        slot.show(images.image(page), images.failed(page))
    }

    private fun showImage(page: Int) {
        slots[page]?.show(images.image(page), images.failed(page))
    }

    private fun drawOverlays(page: Int) {
        slots[page]?.drawOverlays(overlays.on(page))
    }

    private companion object {
        /** The zoom factor of one wheel step (40 units, as `ZoomControls` of the previews). */
        const val WHEEL_STEP = 1.1
        const val WHEEL_UNIT = 40.0

        /** The width a vertical scroll bar takes until the scroll pane made its own. */
        const val SCROLL_BAR = 16.0

        /** How much wider than the viewport the content may be and still count as fitting (rounding of a fit). */
        const val ROUNDING = 0.5
    }
}
