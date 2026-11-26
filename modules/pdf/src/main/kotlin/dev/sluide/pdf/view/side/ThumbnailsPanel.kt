package dev.sluide.pdf.view.side

import dev.sluide.pdf.engine.PageSize
import dev.sluide.pdf.engine.PageTarget
import dev.sluide.pdf.view.PdfFileView
import dev.sluide.pdf.view.WorkPriority
import dev.sluide.pdf.view.toImage
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.Skin
import javafx.scene.control.skin.ListViewSkin
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Window
import kotlin.math.max

/**
 * The Thumbnails tab of the side panel (ADR 0192): the pages as small images ([THUMBNAIL_WIDTH] wide) with their
 * numbers, rendered on the view's render worker at [WorkPriority.THUMBNAIL] for the cells shown, and only while the
 * tab shows in the open panel ([active]). A click on a page, or Enter on the selected one, scrolls the pages to it and
 * gives them the focus. The selection follows the current page without scrolling the pages, except that a picked page
 * stays selected until the user scrolls or the pages move away from the page the jump left them on. FX thread only.
 */
internal class ThumbnailsPanel(
    private val view: PdfFileView,
) {
    private val cache = ThumbnailCache<Image>(::submit, ::dropCancelled, ::changed)
    private val cells = mutableListOf<ThumbnailCell>()
    private val list =
        object : ListView<Int>() {
            override fun createDefaultSkin(): Skin<*> = ThumbnailListSkin(this).also { it.flow.laidOut = ::laidOut }
        }
    val node: Node get() = list

    /** Whether the thumbnails show: the Thumbnails tab is selected in the open panel. */
    private var active = false

    /** Whether the list still has to scroll to the selected page: it followed the pages while it was hidden. */
    private var scrollPending = false

    /** The sizes of the pages listed, those of the document shown. */
    private var sizes: List<PageSize> = emptyList()

    /** The output scale of the list's window (2 on a Retina screen), 1 while it has none. */
    private val outputScale =
        list
            .sceneProperty()
            .flatMap(Scene::windowProperty)
            .flatMap(Window::outputScaleXProperty)
            .map { it.toDouble() }
            .orElse(1.0)

    init {
        list.setCellFactory { ThumbnailCell().also { cells += it } }
        list.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (event.button == MouseButton.PRIMARY && event.clickCount == 1) clickedPage(event)?.let(::jump)
        }
        list.addEventHandler(KeyEvent.KEY_PRESSED) { event ->
            if (event.code == KeyCode.ENTER) {
                list.selectionModel.selectedIndex
                    .takeIf { it >= 0 }
                    ?.let(::jump)
                event.consume()
            }
        }
        view.info.subscribe { info ->
            sizes = info?.pageSizes.orEmpty()
            list.items.setAll(sizes.indices.toList())
            follow(view.currentPage.get())
        }
        view.currentPage.subscribe { page -> follow(page.toInt()) }
        // The page may stay the same (a picked page below the last one the pages can scroll to the top): follow anyway.
        view.userScrolled += { follow(view.currentPage.get()) }
        view.onReload += {
            cache.clear()
            cells.forEach(ThumbnailCell::showImage)
            laidOut()
        }
        view.onDispose += cache::clear
    }

    /** Shows the thumbnails (renders those of the cells shown) or stops rendering them. */
    fun active(shown: Boolean) {
        active = shown
        if (shown) {
            scrollPending = true
            laidOut()
        } else {
            cache.show(emptyList())
        }
    }

    /** Renders the thumbnails of the cells shown, after scrolling to the selected page once the list has cells. */
    private fun laidOut() {
        if (!active) return
        if (scrollPending && shownPages().isNotEmpty()) {
            scrollPending = false
            showRow(list, list.selectionModel.selectedIndex)
        }
        cache.show(shownPages())
    }

    /**
     * The pages of the cells in the list's viewport; until the list laid out new items (a shorter document after a
     * reload) its cells may still have indices beyond them, which are left out.
     */
    private fun shownPages(): List<Int> {
        val flow = (list.skin as? ThumbnailListSkin)?.flow ?: return emptyList()
        val first = flow.firstVisibleCell?.index ?: return emptyList()
        val last = flow.lastVisibleCell?.index ?: return emptyList()
        return (first..last).filter { it in sizes.indices }
    }

    /** Selects [page] and scrolls the list to it; a page the list does not have (yet) the list ignores. */
    private fun follow(page: Int) {
        list.selectionModel.select(page)
        showRow(list, page)
    }

    private fun clickedPage(event: MouseEvent): Int? {
        var hit = event.target as? Node
        while (hit != null && hit !== list) {
            if (hit is ListCell<*>) return if (hit.isEmpty) null else hit.index
            hit = hit.parent
        }
        return null
    }

    private fun jump(page: Int) {
        view.scrollTo(PageTarget(page))
        // After the scroll, whose page change the selection followed: the pick stays until the next one.
        list.selectionModel.select(page)
        view.pages.requestFocus()
    }

    private fun submit(request: ThumbnailCache.Request<Image>) {
        val scale = thumbnailRenderScale(sizes[request.page], outputScale.value)
        view.renderWorker.submit(
            WorkPriority.THUMBNAIL,
            { document -> document.render(request.page, scale, request.cancelled::get)?.let(::toImage) },
            request::arrived,
            { _ -> request.failed() },
            tag = request,
        )
    }

    private fun dropCancelled() =
        view.renderWorker.cancelQueued { job -> (job.tag as? ThumbnailCache.Request<*>)?.cancelled?.get() == true }

    private fun changed(page: Int) = cells.filter { !it.isEmpty && it.item == page }.forEach(ThumbnailCell::showImage)

    /** A page: its image on white paper of the thumbnail's size (blank until rendered), its number below. */
    private inner class ThumbnailCell : ListCell<Int>() {
        private val image = ImageView().apply { styleClass += "pdf-thumbnail-image" }
        private val paper = StackPane(image).apply { styleClass += "pdf-thumbnail-page" }
        private val number = Label().apply { styleClass += "pdf-thumbnail-number" }
        private val content =
            VBox(paper, number).apply {
                styleClass += "pdf-thumbnail"
                alignment = Pos.TOP_CENTER
            }

        override fun updateItem(
            page: Int?,
            empty: Boolean,
        ) {
            super.updateItem(page, empty)
            text = null
            if (empty || page == null) {
                graphic = null
                image.image = null
                return
            }
            val size = sizes[page]
            val scale = thumbnailScale(size)
            val width = max(size.width * scale, 1.0)
            val height = max(size.height * scale, 1.0)
            paper.setMinSize(width, height)
            paper.setPrefSize(width, height)
            paper.setMaxSize(width, height)
            image.fitWidth = width
            image.fitHeight = height
            number.text = (page + 1).toString()
            showImage()
            graphic = content
        }

        fun showImage() {
            image.image = item?.takeUnless { isEmpty }?.let(cache::image)
        }
    }
}

/** A list skin whose flow tells [ThumbnailsPanel] after each layout, when the cells shown may have changed. */
private class ThumbnailListSkin(
    list: ListView<Int>,
) : ListViewSkin<Int>(list) {
    val flow: LayoutReportingFlow get() = virtualFlow as LayoutReportingFlow

    override fun createVirtualFlow(): VirtualFlow<ListCell<Int>> = LayoutReportingFlow()
}

private class LayoutReportingFlow : VirtualFlow<ListCell<Int>>() {
    var laidOut: () -> Unit = {}

    override fun layoutChildren() {
        super.layoutChildren()
        laidOut()
    }
}
