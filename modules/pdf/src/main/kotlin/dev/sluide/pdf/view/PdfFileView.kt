package dev.sluide.pdf.view

import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FocusWhenShown
import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.engine.PageTarget
import dev.sluide.pdf.engine.PdfDocument
import javafx.beans.property.ReadOnlyIntegerProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ObservableValue
import javafx.geometry.Bounds
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.control.ContextMenu
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import java.nio.file.Path
import java.util.concurrent.ExecutorService

/**
 * A PDF in a tab (ADR 0192): all pages in one column ([PageColumn]), rendered on the [renderWorker], the texts on the
 * [textWorker]. Opening returns at once. FX thread only.
 *
 * The `internal` members are the hooks of the features built on the view (selection and links, the side panel,
 * search); their names and types are fixed. Positions on pages are points of the page as displayed (ADR 0192).
 */
internal class PdfFileView(
    file: Path,
    renderExecutor: ExecutorService,
    textExecutor: ExecutorService,
) : FileView {
    /** The file shown; follows moves. */
    var file: Path = file
        private set

    /** The worker of the pages, thumbnails, outline and links. */
    val renderWorker = PdfWorker(renderExecutor)

    /** The worker of the page texts and search. */
    val textWorker = PdfWorker(textExecutor)

    /** The page texts, extracted on the [textWorker]. */
    val texts = PageTexts(textWorker)

    private val column = PageColumn(renderWorker)
    private val zoom = PdfZoom(column)

    /** The toolbar, with the slots of the side panel's and the search's toggles. */
    val toolbar = PdfToolbar(zoom.node)
    private val pageArea = StackPane(column.node).apply { styleClass += "pdf-page-area" }
    private var disposed = false

    /** The side panels and the pages between them. */
    val split = PdfSplit(pageArea) { !disposed }
    private val root = BorderPane(split.node, toolbar.node, null, null, null).apply { styleClass += "pdf-view" }
    private val pendingFocus = FocusWhenShown()
    private val infoWrapper = ReadOnlyObjectWrapper<PdfInfo?>(null)

    /** What is known of the open document; `null` while nothing is open. */
    val info: ObservableValue<PdfInfo?> = infoWrapper.readOnlyProperty

    /** The page under the viewport's vertical middle (0-based). */
    val currentPage: ReadOnlyIntegerProperty get() = column.currentPage

    /** Called when the user scrolls the pages (wheel, keys, scroll bar), not on [scrollTo], a zoom or a resize. */
    val userScrolled: MutableList<() -> Unit> get() = column.userScrolled

    /** The highlights above the page images. */
    val overlays: PageOverlays get() = column.overlays

    /** The focusable page column, for the event handlers and filters of other features. */
    val pages: Node get() = column.node

    /** The cursor over the pages. */
    var pageCursor: Cursor?
        get() = column.cursor
        set(value) {
            column.cursor = value
        }

    /** The context menu of the pages, shown on a secondary click on a page while it has items; empty here. */
    val pageContextMenu: ContextMenu get() = column.contextMenu

    /** Called on the FX thread after the document was opened again with both workers (a reload). */
    val onReload = mutableListOf<() -> Unit>()

    /** Called once by [dispose], before the workers stop. */
    val onDispose = mutableListOf<() -> Unit>()

    override var host: FileViewHost? = null

    override val node: Node get() = root

    init {
        root.stylesheets += STYLESHEET
        wire()
        open(null)
    }

    private fun wire() {
        zoom.install(root)
        root.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (event.isShortcutDown && event.code == KeyCode.G) {
                toolbar.focusPageField()
                event.consume()
            }
        }
        toolbar.installPageField(
            column.currentPage,
            { info.value?.pageCount ?: 0 },
            { page -> scrollTo(PageTarget(page)) },
            ::focusText,
        )
        info.subscribe { opened -> toolbar.pageCount.text = "/ ${opened?.pageCount ?: 0}" }
        toolbar.openExternally.setOnAction { host?.openWebPage(file.toUri()) }
    }

    /** Scrolls [target] to the top of the viewport; an app scroll, which does not call [userScrolled]. */
    fun scrollTo(target: PageTarget) = column.scrollTo(target)

    /** The point of a page under the scene point, in points; `null` outside the pages. */
    fun pagePointAt(
        sceneX: Double,
        sceneY: Double,
    ): PagePoint? = column.pagePointAt(sceneX, sceneY)

    /** [rect] of [page] in scene coordinates; `null` for a page that does not exist. */
    fun pageRectToScene(
        page: Int,
        rect: PageRect,
    ): Bounds? = column.pageRectToScene(page, rect)

    /** Scrolls the least to show [rect] of [page], centring it vertically if it was off-screen; an app scroll. */
    fun scrollIntoView(
        page: Int,
        rect: PageRect,
    ) = column.scrollIntoView(page, rect)

    /** For tests: the page column. */
    internal val pageColumn: PageColumn get() = column

    private fun open(at: PageTarget?) {
        renderWorker.reopen(file, null, ::readInfo) { opening ->
            if (opening is Opening.Opened) opened(opening.value, at)
        }
    }

    private fun opened(
        opened: PdfInfo,
        at: PageTarget?,
    ) {
        texts.clear()
        textWorker.reopen(file, null, { }) { }
        infoWrapper.set(opened)
        column.show(opened.pageSizes, at)
    }

    override fun reload() {}

    override fun focusText() = column.node.requestFocus()

    override fun focus() = pendingFocus.request(root) { if (!disposed) focusText() }

    override fun dispose() {
        if (disposed) return
        onDispose.toList().forEach { it() }
        disposed = true
        pendingFocus.cancel()
        renderWorker.dispose()
        textWorker.dispose()
        column.dispose()
        texts.clear()
    }

    private companion object {
        val STYLESHEET: String =
            checkNotNull(PdfFileView::class.java.getResource("/dev/sluide/pdf/pdf-view.css")).toExternalForm()

        /** Reads what the view needs of a document, on the render worker right after it opened. */
        fun readInfo(document: PdfDocument) = PdfInfo(document.pageCount, document.pageSizes, document.outline())
    }
}
