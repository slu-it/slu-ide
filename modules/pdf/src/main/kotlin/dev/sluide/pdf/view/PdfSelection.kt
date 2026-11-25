package dev.sluide.pdf.view

import dev.sluide.pdf.engine.LinkTarget
import dev.sluide.pdf.engine.PageLink
import javafx.beans.property.SimpleBooleanProperty
import javafx.scene.Cursor
import javafx.scene.control.MenuItem
import javafx.scene.control.Tooltip
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import kotlin.math.hypot

/**
 * Selection, copy and links of a [PdfFileView] (ADR 0192): a drag over the text selects it (a double-click a word),
 * Shortcut+A selects all, Shortcut+C and the context menu copy, Escape or a click clears it. The selection is kept in
 * text offsets ([TextSelection]) and drawn in the `SELECTION` overlay layer for the shown pages. A click on a link
 * follows it; the cursor and a tooltip tell what is under the pointer. FX thread only.
 */
internal class PdfSelection private constructor(
    private val view: PdfFileView,
) {
    private var selection: TextSelection? = null

    /** Counted on every change of the selection and on reload, so that a late text does not draw a stale one. */
    private var version = 0
    private var press: Press? = null
    private var hover = 0
    private val links = HashMap<Int, List<PageLink>>()
    private val requested = HashSet<Int>()
    private val tag = Any()
    private val hasSelection = SimpleBooleanProperty(false)
    private val copyItem = MenuItem("Copy")
    private val selectAllItem = MenuItem("Select All")
    private val tooltip = Tooltip()
    private var tooltipInstalled = false

    /** A primary press and what is known of it: where, the char under it, the char under the pointer now. */
    private class Press(
        val sceneX: Double,
        val sceneY: Double,
        val point: PagePoint?,
    ) {
        var dragged = false
        var anchor: TextPosition? = null
        var pointer: TextPosition? = null
    }

    private fun wire() {
        val pages = view.pages
        pages.addEventHandler(MouseEvent.MOUSE_PRESSED, ::pressed)
        pages.addEventHandler(MouseEvent.MOUSE_DRAGGED, ::dragged)
        pages.addEventHandler(MouseEvent.MOUSE_RELEASED, ::released)
        pages.addEventHandler(MouseEvent.MOUSE_MOVED, ::moved)
        pages.addEventHandler(KeyEvent.KEY_PRESSED, ::key)
        copyItem.disableProperty().bind(hasSelection.not())
        copyItem.setOnAction { copy() }
        selectAllItem.setOnAction { selectAll() }
        view.pageContextMenu.items += listOf(copyItem, selectAllItem)
        view.shownPages.subscribe { _ ->
            fetchLinks()
            paint()
        }
        view.onReload += ::reset
        view.onDispose += ::dispose
    }

    private fun pressed(event: MouseEvent) {
        if (event.button != MouseButton.PRIMARY) return
        view.pages.requestFocus()
        val point = view.pagePointAt(event.sceneX, event.sceneY)
        val current = Press(event.sceneX, event.sceneY, point)
        press = current
        if (point == null) return
        resolve(point) { position ->
            if (press === current) {
                current.anchor = position
                extend(current)
            }
        }
        if (event.clickCount == 2) {
            view.texts.textOf(point.page) { text ->
                val index = TextSelection.charAt(text, point.x, point.y)
                if (index != null && press === current) {
                    val word = TextSelection.wordAt(text, index)
                    select(TextSelection(point.page, word.first, point.page, word.last))
                }
            }
        }
    }

    private fun dragged(event: MouseEvent) {
        val current = press ?: return
        if (hypot(event.sceneX - current.sceneX, event.sceneY - current.sceneY) > CLICK_SLOP) current.dragged = true
        if (!current.dragged) return
        val point = view.pagePointAt(event.sceneX, event.sceneY) ?: return
        resolve(point) { position ->
            if (press === current) {
                current.pointer = position
                extend(current)
            }
        }
    }

    private fun released(event: MouseEvent) {
        if (event.button != MouseButton.PRIMARY) return
        val current = press ?: return
        press = null
        if (current.dragged || event.clickCount != 1) return
        select(null)
        val point = current.point ?: return
        linkAt(links[point.page].orEmpty(), point.x, point.y)?.let { follow(it.target) }
    }

    private fun moved(event: MouseEvent) {
        val current = ++hover
        val point = view.pagePointAt(event.sceneX, event.sceneY)
        val link = point?.let { linkAt(links[it.page].orEmpty(), it.x, it.y) }
        showTooltip(link)
        when {
            point == null -> {
                view.pageCursor = null
            }

            link != null -> {
                view.pageCursor = Cursor.HAND
            }

            else -> {
                view.texts.textOf(point.page) { text ->
                    if (hover == current) {
                        val over = TextSelection.charUnder(text, point.x, point.y) != null
                        view.pageCursor = if (over) Cursor.TEXT else null
                    }
                }
            }
        }
    }

    private fun key(event: KeyEvent) {
        when {
            event.isShortcutDown && event.code == KeyCode.A -> selectAll()
            event.isShortcutDown && event.code == KeyCode.C -> copy()
            event.code == KeyCode.ESCAPE && selection != null -> select(null)
            else -> return
        }
        event.consume()
    }

    private fun resolve(
        point: PagePoint,
        onPosition: (TextPosition) -> Unit,
    ) = view.texts.textOf(point.page) { text ->
        TextSelection.charAt(text, point.x, point.y)?.let { onPosition(TextPosition(point.page, it)) }
    }

    private fun extend(current: Press) {
        val from = current.anchor ?: return
        val to = current.pointer ?: return
        select(TextSelection.between(from, to))
    }

    private fun select(range: TextSelection?) {
        if (range == selection) return
        selection = range
        hasSelection.set(range != null)
        version++
        view.overlays.clear(OverlayLayer.SELECTION)
        paint()
    }

    private fun selectAll() {
        val count = view.info.value?.pageCount ?: return
        if (count > 0) select(TextSelection.all(count - 1))
    }

    /** Draws the selection on the shown pages; pages that left the view keep what they had. */
    private fun paint() {
        val range = selection ?: return
        val current = version
        for (page in view.shownPages.value) {
            if (page !in range.startPage..range.endPage) continue
            view.texts.textOf(page) { text ->
                if (version == current) {
                    val chars = range.rangeOn(page, text.text.length)
                    val rects = chars?.let { textRects(text, it.first, it.last + 1) }.orEmpty()
                    view.overlays.set(OverlayLayer.SELECTION, page, rects)
                }
            }
        }
    }

    private fun copy() {
        val range = selection ?: return
        val pages = range.startPage..range.endPage
        val collected = HashMap<Int, String>()
        for (page in pages) {
            view.texts.plainTextOf(page) { text ->
                collected[page] = text
                if (collected.size == pages.count()) {
                    val copied = range.copyText(collected::getValue)
                    if (copied.isNotEmpty()) {
                        Clipboard.getSystemClipboard().setContent(
                            ClipboardContent().also { it.putString(copied) },
                        )
                    }
                }
            }
        }
    }

    private fun follow(target: LinkTarget) {
        when (target) {
            is LinkTarget.Page -> view.scrollTo(target.target)
            is LinkTarget.Web -> view.host?.openWebPage(target.uri)
        }
    }

    private fun fetchLinks() {
        for (page in view.shownPages.value) {
            if (requested.add(page)) {
                view.renderWorker.submit(WorkPriority.NEAR, { it.links(page) }, { links[page] = it }, tag = tag)
            }
        }
    }

    private fun showTooltip(link: PageLink?) {
        if (link != null) {
            tooltip.text = link.target.label()
            if (!tooltipInstalled) Tooltip.install(view.pages, tooltip)
        } else if (tooltipInstalled) {
            Tooltip.uninstall(view.pages, tooltip)
            tooltip.hide()
        }
        tooltipInstalled = link != null
    }

    /** A reload: nothing of the old document is kept. */
    private fun reset() {
        select(null)
        press = null
        links.clear()
        requested.clear()
        view.renderWorker.cancelQueued { it.tag === tag }
        fetchLinks()
    }

    private fun dispose() {
        version++
        press = null
        view.renderWorker.cancelQueued { it.tag === tag }
        showTooltip(null)
    }

    companion object {
        /** How far the pointer may move between press and release and still make a click, in pixels. */
        const val CLICK_SLOP = 3.0

        fun install(view: PdfFileView) = PdfSelection(view).wire()
    }
}
