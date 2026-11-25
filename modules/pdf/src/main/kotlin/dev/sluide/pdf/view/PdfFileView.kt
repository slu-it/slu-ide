package dev.sluide.pdf.view

import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FocusWhenShown
import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.engine.PageTarget
import dev.sluide.pdf.engine.PdfDocument
import dev.sluide.pdf.engine.PdfOpenResult
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
import java.util.Locale
import java.util.concurrent.ExecutorService
import kotlin.io.path.extension

/**
 * A PDF in a tab (ADR 0192): all pages in one column ([PageColumn]), rendered on the [renderWorker], the texts on the
 * [textWorker], each with a document of its own. Opening returns at once ("Loading…" until the render document opened);
 * the text document opens once the render document did, with the same password. A PDF with a user password asks for it
 * ([askPassword]); the password is kept in memory only, for reloads, and zeroed on [dispose]. A document that cannot be
 * shown gets a centred notice in place of the pages. FX thread only.
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
    private val notice = PdfNotice(column.node)
    private val pageArea = StackPane(column.node, notice.node).apply { styleClass += "pdf-page-area" }
    private var disposed = false

    /** The side panels and the pages between them. */
    val split = PdfSplit(pageArea)
    private val root = BorderPane(split.node, toolbar.node, null, null, null).apply { styleClass += "pdf-view" }
    private val pendingFocus = FocusWhenShown()
    private val infoWrapper = ReadOnlyObjectWrapper<PdfInfo?>(null)

    /** What is known of the open document; `null` while nothing is open. */
    val info: ObservableValue<PdfInfo?> = infoWrapper.readOnlyProperty

    /** The page under the viewport's vertical middle (0-based). */
    val currentPage: ReadOnlyIntegerProperty get() = column.currentPage

    /**
     * The pages that intersect the viewport; [IntRange.EMPTY] while nothing is open or a notice shows. A non-empty
     * range, and the [currentPage] reported with it, always lies within `0 until info.value.pageCount`.
     */
    val shownPages: ObservableValue<IntRange> get() = column.shownPages

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

    /** Asks for the password; returns a function that closes the prompt. Tests replace it (ADR 0084). */
    var askPassword: (PasswordRequest) -> (() -> Unit) = PasswordPrompt::show

    /** The password that opened the document, if it needed one; never stored, zeroed on [dispose]. */
    private var password: CharArray? = null

    /** Closes the password prompt while it shows. */
    private var closePrompt: (() -> Unit)? = null

    /** Whether a document was shown: every reopen after that is a reload, also one back from a notice. */
    private var shownOnce = false

    /** The password of an attempt on its way, zeroed if [dispose] drops its answer. */
    private var attempting: CharArray? = null

    /**
     * A focus request that came while a notice hid the pages, with the scene's focus owner at that moment: the pages
     * take the focus once they show, unless it moved elsewhere meanwhile.
     */
    private var focusOnShow: FocusRequest? = null

    private class FocusRequest(
        val owner: Node?,
    )

    private val stamp = FileStamp { file }

    override val node: Node get() = root

    init {
        root.stylesheets += STYLESHEET
        wire()
        showNotice(PdfNotice.LOADING)
        open(null, reload = false)
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

    /** For tests: the notice, and the password kept. */
    internal val pageNotice: PdfNotice get() = notice
    internal val keptPassword: CharArray? get() = password
    internal val attemptingPassword: CharArray? get() = attempting

    /** Opens the file with the password kept, scrolled to [at] once shown; [reload] says whether it was shown before. */
    private fun open(
        at: PageTarget?,
        reload: Boolean,
    ) = renderWorker.reopen(file, password?.let(::String), ::readInfo) { opening -> opened(opening, at, reload) }

    private fun opened(
        opening: Opening<PdfInfo>,
        at: PageTarget?,
        reload: Boolean,
    ) {
        when (opening) {
            is Opening.Opened -> {
                show(opening.value, at, reload)
            }

            is Opening.Failed -> {
                val text = noticeText(opening.result)
                if (text == null) ask(at, reload) else fail(text)
            }
        }
    }

    private fun show(
        opened: PdfInfo,
        at: PageTarget?,
        reload: Boolean,
    ) {
        texts.clear()
        textWorker.reopen(file, password?.let(::String), { }) { opening ->
            if (reload && opening is Opening.Opened) onReload.toList().forEach { it() }
        }
        notice.hide()
        enableControls(true)
        // No pages of the old document under the new info, and none of the new one without it.
        column.clear()
        infoWrapper.set(opened)
        column.show(opened.pageSizes, at)
        shownOnce = true
        focusOnShow?.let { request ->
            focusOnShow = null
            if (root.scene?.focusOwner === request.owner) column.node.requestFocus()
        }
    }

    private fun fail(text: String) {
        clearDocument()
        showNotice(text)
    }

    /** Shows no document: the pages, the info and the text document go. */
    private fun clearDocument() {
        column.clear()
        infoWrapper.set(null)
        texts.clear()
        textWorker.close()
    }

    private fun showNotice(
        text: String,
        actionText: String? = null,
        onAction: () -> Unit = {},
    ) {
        notice.show(text, actionText, onAction)
        enableControls(false)
    }

    private fun enableControls(enabled: Boolean) {
        toolbar.pageField.isDisable = !enabled
        zoom.node.isDisable = !enabled
    }

    /** Asks for the password (the one kept, if any, no longer opens the file) behind the password notice. */
    private fun ask(
        at: PageTarget?,
        reload: Boolean,
    ) {
        forgetPassword()
        clearDocument()
        showNotice(PdfNotice.PROTECTED, PdfNotice.ENTER_PASSWORD) { ask(at, reload) }
        closePrompt =
            askPassword(
                PasswordRequest(
                    root.scene?.window,
                    file.fileName.toString(),
                    { typed, done -> attempt(typed.copyOf(), at, reload, done) },
                    { closePrompt = null },
                ),
            )
    }

    /** Opens the file with [candidate]; a wrong one keeps the prompt open, anything else closes it. */
    private fun attempt(
        candidate: CharArray,
        at: PageTarget?,
        reload: Boolean,
        done: (Boolean) -> Unit,
    ) {
        attempting = candidate
        renderWorker.reopen(
            file,
            String(candidate),
            ::readInfo,
        ) { opening -> attempted(opening, candidate, at, reload, done) }
    }

    private fun attempted(
        opening: Opening<PdfInfo>,
        candidate: CharArray,
        at: PageTarget?,
        reload: Boolean,
        done: (Boolean) -> Unit,
    ) {
        attempting = null
        val wrong = opening is Opening.Failed && opening.result == PdfOpenResult.WrongPassword
        if (wrong) {
            candidate.fill(ZERO)
        } else {
            closePrompt = null
            if (opening is Opening.Opened) password = candidate else candidate.fill(ZERO)
        }
        done(!wrong)
        if (!wrong) opened(opening, at, reload)
    }

    private fun forgetPassword() {
        password?.fill(ZERO)
        password = null
    }

    /**
     * Opens the file again if it changed, keeping the position; one that shows no pages (a notice) is opened as the
     * first time, unless the password prompt shows.
     */
    override fun reload() {
        if (disposed || !stamp.update()) return
        if (info.value != null) {
            open(column.topTarget(), reload = true)
        } else if (closePrompt == null) {
            open(null, reload = shownOnce)
        }
    }

    /** Follows the file to [to] (the explorer moved it) if it is still a PDF. */
    override fun moved(to: Path): Boolean {
        if (disposed || to.extension.lowercase(Locale.ROOT) != "pdf") return false
        file = to
        return true
    }

    override fun focusText() {
        // A hidden node does not take the focus: the pages take it once they show.
        if (notice.shown) {
            focusOnShow = FocusRequest(root.scene?.focusOwner)
        } else {
            column.node.requestFocus()
        }
    }

    override fun focus() = pendingFocus.request(root) { if (!disposed) focusText() }

    override fun dispose() {
        if (disposed) return
        onDispose.toList().forEach { it() }
        disposed = true
        pendingFocus.cancel()
        focusOnShow = null
        renderWorker.dispose()
        textWorker.dispose()
        column.dispose()
        texts.clear()
        closePrompt?.invoke()
        forgetPassword()
        attempting?.fill(ZERO)
        attempting = null
    }

    private companion object {
        const val ZERO = '\u0000'

        val STYLESHEET: String =
            checkNotNull(PdfFileView::class.java.getResource("/dev/sluide/pdf/pdf-view.css")).toExternalForm()

        /** Reads what the view needs of a document, on the render worker right after it opened. */
        fun readInfo(document: PdfDocument) = PdfInfo(document.pageCount, document.pageSizes, document.outline())
    }
}
