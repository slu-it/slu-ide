package dev.sluide.ebooks.view

import dev.sluide.ebooks.BookPage
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FocusWhenShown
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.previews.DocumentZoom
import dev.sluide.previews.TextMarks
import javafx.animation.Animation
import javafx.animation.PauseTransition
import javafx.application.ColorScheme
import javafx.application.Platform
import javafx.beans.property.SimpleObjectProperty
import javafx.concurrent.Worker
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.web.WebView
import javafx.util.Duration
import netscape.javascript.JSObject
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import kotlin.io.path.extension
import kotlin.io.path.fileSize

/** The delays and the size limit of an [EpubFileView]; tests shorten them. */
internal class EpubViewConfig(
    val scrollDelay: Duration = Duration.millis(150.0),
    val largeBookChars: Int = BookLoading.LARGE_BOOK_CHARS,
)

/**
 * An EPUB in a tab (ADR 0165): a toolbar, the Contents panel, the book as one page in a WebView and a right pane that
 * later features fill. FX thread only. Parsing and building run on [loadExecutor] (a late result of a replaced load,
 * or after [dispose], is ignored); [position] remembers the reading position.
 *
 * The `internal` members are the hooks of Read Aloud and the search. Offsets are whole-book offsets, in chapter mode
 * too, except where a member says "local": [webView] and [marks] belong to the page that is shown, which in chapter
 * mode is one chapter with offsets starting at 0; [toLocal] and [toWhole] translate (see [ShownPage]).
 */
internal class EpubFileView(
    private val location: BookLocation,
    private val position: BookPosition,
    private val loadExecutor: ExecutorService,
    private val config: EpubViewConfig = EpubViewConfig(),
) : FileView {
    private val loading = BookLoading(PageBridge.SCRIPT, config.largeBookChars)

    /** The web view of the page that is shown: its text offsets are local to that page (see [toLocal]). */
    internal val webView = WebView().apply { isContextMenuEnabled = false }
    private val engine = webView.engine
    private val textMarks = TextMarks(engine) { webView.zoom }
    private val zoom = DocumentZoom(webView).also { it.install() }
    private val toolbar = EbookToolbar(zoom.node)
    private val contentsPanel = ContentsPanel { offset -> jumpFromContents(offset) }
    private val noticeBar = HBox()
    private val reader = BorderPane()
    private val overlayText = Label().apply { styleClass += "ebook-overlay-text" }
    private val overlay = StackPane(overlayText).apply { styleClass += "ebook-overlay" }
    private val root = StackPane(reader, overlay).apply { setMinSize(0.0, 0.0) }
    private val pendingFocus = FocusWhenShown()
    private val scrollTimer = PauseTransition(config.scrollDelay)

    internal val readAloudSlot: HBox get() = toolbar.readAloudSlot
    internal val searchSlot: HBox get() = toolbar.searchSlot
    internal val rightPane = StackPane()
    private val split =
        ReaderSplit(contentsPanel.node, webView, rightPane, { toolbar.contents.isSelected }, { !disposed })
    internal val onPageLoaded = mutableListOf<() -> Unit>()

    /** The slot between the toolbar and the page (Read Aloud's setup notice). */
    internal val bannerSlot = VBox()

    /** Called once by [dispose], before the page is dropped. */
    internal val onDispose = mutableListOf<() -> Unit>()

    /** Whether a single click on text marks the start; Read Aloud switches it off while it reads. */
    internal var startClicks = true

    /** The tab's activity marker; Read Aloud binds it to its own. */
    override val activity = SimpleObjectProperty(ViewActivity.NONE)

    /** Called on the FX thread for genuine user scrolls only (wheel, keys, touch, scrollbar), not for jumps. */
    internal val onUserScroll = mutableListOf<() -> Unit>()

    /** Called for navigations of the user that move the page without a scroll: Contents, chapter buttons, links. */
    internal val onUserNavigate = mutableListOf<() -> Unit>()

    private var loaded: LoadedBook? = null
    private var shown: ShownPage? = null
    private var pageReady = false
    private var disposed = false

    /** Counts loads of the book; a result of an older one is dropped. */
    private var loadGeneration = 0

    /** Counts builds of a page (a load, a chapter, a style change); a result of an older one is dropped. */
    private var pageGeneration = 0
    private var loadPending = false
    private var restyleAt: Int? = null
    private var dark = false
    private var current = 0
    private var pendingReveal: Int? = null
    private var readingPosition = 0
    private var stamp: Pair<Long, Any?>? = stampOf()

    /** Named marks (whole-book offsets, end exclusive), shown again after every load of a page. */
    private val named = linkedMapOf<String, Pair<Int, Int>>()

    private val bridge = PageBridge(::openLink, ::anchorFollowed, ::anchorElsewhere, ::textClicked)

    /**
     * The whole book once loaded (else null): text, skip ranges, chapters, contents and anchors, whole-book offsets.
     * Its `html` is empty; the markup is only in the shown page.
     */
    internal val page: BookPage? get() = loaded?.page.takeIf { pageReady }

    /** The whole book as soon as it is loaded, also while a page (re)loads (unlike [page]); null before. */
    internal val loadedBook: BookPage? get() = loaded?.page

    /**
     * The marks of the shown page, which takes LOCAL offsets in chapter mode (see [toLocal]); null until loaded. Its
     * `onUserScroll` belongs to the view and must not be reassigned: use the view's [onUserScroll].
     */
    internal val marks: TextMarks? get() = textMarks.takeIf { pageReady }

    override var host: FileViewHost? = null
        set(value) {
            field = value
            location.projectFolder = value?.projectFolder
        }

    override val node: Node get() = root

    init {
        root.stylesheets +=
            checkNotNull(EpubFileView::class.java.getResource("/dev/sluide/ebooks/ebook-view.css")).toExternalForm()
        buildLayout()
        wire()
        showOverlay("Loading…")
        // The host is set right after creating the view, and the saved position lives under its project folder.
        Platform.runLater { if (!disposed && loaded == null && !loadPending) startLoad(null, firstOpen = true) }
    }

    private fun buildLayout() {
        val close =
            Button(null, FontIcon(MaterialDesignC.CLOSE)).apply {
                isFocusTraversable = false
                styleClass += "ebook-button"
                setOnAction {
                    noticeBar.isVisible = false
                    noticeBar.isManaged = false
                }
            }
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        noticeBar.children.setAll(Label("Fixed-layout book, shown as flowing text."), spacer, close)
        noticeBar.styleClass += "ebook-notice-bar"
        noticeBar.isVisible = false
        noticeBar.isManaged = false
        reader.top = VBox(noticeBar, toolbar.node, bannerSlot)
        reader.center = split.node
    }

    private fun wire() {
        toolbar.contents.selectedProperty().subscribe { _ -> split.sync() }
        rightPane.children.subscribe { split.sync() }
        toolbar.bookStyle.selectedProperty().subscribe { _ -> restyle() }
        toolbar.previous.setOnAction {
            navigated()
            showChapter(current - 1)
        }
        toolbar.next.setOnAction {
            navigated()
            showChapter(current + 1)
        }
        textMarks.onUserScroll = {
            scrollTimer.playFromStart()
            onUserScroll.toList().forEach { it() }
        }
        scrollTimer.setOnFinished { recompute(follow = true) }
        engine.loadWorker.stateProperty().subscribe { state -> if (state == Worker.State.SUCCEEDED) onLoaded() }
        engine.locationProperty().subscribe { address ->
            if (!disposed && !isOwnPage(address)) navigatedAway(address)
        }
        root
            .sceneProperty()
            .flatMap { it.preferences.colorSchemeProperty() }
            .orElse(ColorScheme.LIGHT)
            .subscribe { scheme ->
                dark = scheme == ColorScheme.DARK
                applyDark()
            }
    }

    private fun stampOf(): Pair<Long, Any?>? =
        try {
            location.file.fileSize() to Files.getLastModifiedTime(location.file)
        } catch (_: IOException) {
            null
        }

    // --- Layout ---------------------------------------------------------------------------------------------

    private fun showOverlay(text: String) {
        overlayText.text = text
        overlay.isVisible = true
    }

    // --- Loading --------------------------------------------------------------------------------------------

    /** Reads and builds the book; [target] is where to scroll afterwards, else the saved position (first open). */
    private fun startLoad(
        target: Int?,
        firstOpen: Boolean,
    ) {
        val id = ++loadGeneration
        loadPending = true
        val ownStyle = toolbar.bookStyle.isSelected
        val isDark = dark
        val file = location.file
        loadExecutor.execute {
            val result = loading.load(file, ownStyle, isDark)
            val saved =
                if (result is BookLoad.Ready && target == null) position.read(result.loaded.page.text.length) else null
            Platform.runLater {
                if (id == loadGeneration && !disposed) {
                    loadPending = false
                    loadedResult(result, target ?: saved, saved != null, firstOpen)
                    // The style was toggled while the book was loading: build the page again in the new one.
                    if (result is BookLoad.Ready && toolbar.bookStyle.isSelected != ownStyle) restyle()
                }
            }
        }
    }

    private fun loadedResult(
        result: BookLoad,
        target: Int?,
        fromSaved: Boolean,
        firstOpen: Boolean,
    ) {
        when (result) {
            is BookLoad.Notice -> {
                // A book that cannot be read again stays as it is shown; only a first open has nothing to show.
                if (loaded != null && !firstOpen) return
                loaded = null
                shown = null
                pageReady = false
                reader.isVisible = false
                showOverlay(result.text)
            }

            is BookLoad.Ready -> {
                val book = result.loaded
                // Builds of the old book that are still on their way must not land over this one.
                pageGeneration++
                restyleAt = null
                loaded = book
                reader.isVisible = true
                contentsPanel.show(contentsItems(book.page))
                toolbar.showChapterButtons(book.chapterMode)
                if (firstOpen && book.book.fixedLayout) {
                    noticeBar.isVisible = true
                    noticeBar.isManaged = true
                }
                val start = target ?: 0
                if (fromSaved) setNamedStart(start)
                if (target != null) setPosition(start, save = false)
                val first = result.first
                if (first != null) {
                    display(first, target)
                } else {
                    showChapter(chapterAt(book.page.chapters, start), target, save = false)
                }
            }
        }
    }

    private fun display(
        next: ShownPage,
        reveal: Int?,
    ) {
        shown = next
        pageReady = false
        pendingReveal = reveal
        updateChapterButtons()
        engine.loadContent(next.html)
    }

    private fun onLoaded() {
        val page = shown ?: return
        if (disposed || pageReady) return
        (engine.executeScript("window") as JSObject).setMember(PageBridge.MEMBER, bridge)
        pageReady = true
        overlay.isVisible = false
        applyDark()
        for (name in named.keys.toList()) applyMark(name)
        val reveal = pendingReveal
        pendingReveal = null
        if (reveal != null) {
            textMarks.scrollToTop(page.toLocal(reveal))
            setPosition(reveal, save = false)
        } else if (page.chapter != null) {
            setPosition(
                loaded
                    ?.page
                    ?.chapters
                    ?.get(page.chapter)
                    ?.start ?: 0,
                save = false,
            )
        } else {
            recompute(follow = true, save = false)
        }
        onPageLoaded.toList().forEach { it() }
    }

    /** Builds the page of chapter [index] (large books) and shows it, scrolling to [reveal] if given. */
    private fun showChapter(
        index: Int,
        reveal: Int? = null,
        save: Boolean = true,
    ) {
        val book = loaded ?: return
        if (index !in book.page.chapters.indices) return
        val id = ++pageGeneration
        restyleAt = null
        val ownStyle = toolbar.bookStyle.isSelected
        val isDark = dark
        loadExecutor.execute {
            val next = loading.render(book, index, ownStyle, isDark)
            Platform.runLater { if (id == pageGeneration && !disposed) display(next, reveal) }
        }
        if (reveal == null) setPosition(book.page.chapters[index].start, save = save)
    }

    private fun updateChapterButtons() {
        val chapters = loaded?.page?.chapters.orEmpty()
        current = shown?.chapter ?: 0
        toolbar.previous.isDisable = current <= 0
        toolbar.next.isDisable = current >= chapters.size - 1
    }

    /**
     * The Book Style toggle: builds the page again in the other style, keeping the top of the view. A jump made while
     * it builds wins ([restyleAt]); a book still loading is built in the right style when it arrives.
     */
    private fun restyle() {
        val book = loaded ?: return
        if (loadPending) return
        restyleAt = (if (pageReady) topWhole() else null) ?: readingPosition
        val id = ++pageGeneration
        val ownStyle = toolbar.bookStyle.isSelected
        val isDark = dark
        val chapter = shown?.chapter
        loadExecutor.execute {
            val next = loading.render(book, chapter, ownStyle, isDark)
            Platform.runLater {
                if (id == pageGeneration && !disposed) {
                    val at = restyleAt ?: readingPosition
                    restyleAt = null
                    display(next, at)
                }
            }
        }
    }

    // --- Position -------------------------------------------------------------------------------------------

    private fun topWhole(): Int? {
        val local = textMarks.topOffset() ?: return null
        return shown?.toWhole(local)
    }

    private fun recompute(
        follow: Boolean,
        save: Boolean = true,
    ) {
        if (!pageReady) return
        topWhole()?.let { setPosition(it, follow, save) }
    }

    private fun setPosition(
        offset: Int,
        follow: Boolean = true,
        save: Boolean = true,
    ) {
        val book = loaded?.page ?: return
        readingPosition = offset.coerceIn(0, book.text.length)
        toolbar.progress.text = progressText(book.chapters, book.text.length, readingPosition)
        if (follow) contentsPanel.follow(readingPosition)
        if (save) position.changed(readingPosition)
    }

    private fun navigated() = onUserNavigate.toList().forEach { it() }

    private fun jumpFromContents(offset: Int) {
        navigated()
        jump(offset, follow = false)
        webView.requestFocus()
    }

    private fun jump(
        offset: Int,
        follow: Boolean,
    ) {
        val book = loaded ?: return
        val target = offset.coerceIn(0, book.page.text.length)
        val chapter = if (book.chapterMode) chapterAt(book.page.chapters, target) else null
        if (restyleAt != null) restyleAt = target
        if (chapter != null && shown?.chapter != chapter) {
            showChapter(chapter, target, save = false)
        } else if (!pageReady) {
            pendingReveal = target
        } else {
            shown?.let { textMarks.scrollToTop(it.toLocal(target)) }
        }
        setPosition(target, follow)
    }

    private fun anchorFollowed() {
        navigated()
        Platform.runLater { if (!disposed) recompute(follow = true) }
    }

    /** A link to an id that the shown page does not have: the whole book knows where it is (another chapter). */
    private fun anchorElsewhere(id: String) {
        val target = loaded?.page?.anchors?.get(id) ?: return
        navigated()
        jump(target, follow = true)
    }

    // --- Links, clicks ----------------------------------------------------------------------------------------

    private fun openLink(href: String) {
        externalLink(href)?.let { uri -> host?.openWebPage(uri) }
    }

    /** The WebView went to another page: cancelled by showing the book again, and opened in the browser if it is a link. */
    private fun navigatedAway(address: String) {
        externalLink(address)?.let { uri -> host?.openWebPage(uri) }
        val back = shown ?: return
        val id = pageGeneration
        Platform.runLater { if (!disposed && id == pageGeneration) display(back, readingPosition) }
    }

    private fun textClicked(local: Int) {
        if (!startClicks) return
        val whole = shown?.toWhole(local) ?: return
        setNamedStart(whole)
        applyMark(START)
    }

    private fun setNamedStart(offset: Int) {
        val text = loaded?.page?.text ?: return
        val sentence = SentenceBounds.around(text, offset)
        if (sentence.isEmpty()) return
        named[START] = sentence.first to sentence.last + 1
    }

    private fun applyDark() {
        if (pageReady) engine.executeScript("document.body.classList.toggle('dark', $dark)")
    }

    private fun applyMark(name: String) {
        val range = named[name]
        val local = shown
        if (!pageReady || local == null) return
        val clipped = range?.let { local.localRange(loaded?.page?.chapters.orEmpty(), it.first, it.second) }
        if (clipped == null) textMarks.clear(name) else textMarks.mark(name, clipped.first, clipped.second)
    }

    // --- Hooks ------------------------------------------------------------------------------------------------

    /** The marked start if any, else the offset at the top of the view, else 0; whole-book. */
    internal fun startOffset(): Int = named[START]?.first ?: (if (pageReady) topWhole() else null) ?: 0

    /** Scrolls to the whole-book [offset], loading its chapter if needed, and updates position, contents, progress. */
    internal fun jumpTo(offset: Int) = jump(offset, follow = true)

    /** A jump of the user (a search hit): [jumpTo], and Read Aloud stops following like for Contents or links. */
    internal fun userJumpTo(offset: Int) {
        navigated()
        jumpTo(offset)
    }

    /** The marked start of Read Aloud (whole-book offset of its sentence), null without; set marks a sentence. */
    internal var startMark: Int?
        get() = named[START]?.first
        set(value) {
            if (value == null) named.remove(START) else setNamedStart(value)
            applyMark(START)
        }

    /** The local offset on the shown page of the whole-book [offset]; null if the page does not show it. */
    internal fun toLocal(offset: Int): Int? =
        if (pageReady) shown?.localOffset(loaded?.page?.chapters.orEmpty(), offset) else null

    /** The whole-book offset of the local [local] offset of the shown page (for [marks] and [webView]). */
    internal fun toWhole(local: Int): Int = shown?.toWhole(local) ?: local

    /** Marks the whole-book range `[start, end)` under [name]; in chapter mode only the part in the shown chapter. */
    internal fun markRange(
        name: String,
        start: Int,
        end: Int,
    ) {
        named[name] = start to end
        applyMark(name)
    }

    internal fun clearMark(name: String) {
        named.remove(name)
        applyMark(name)
    }

    /** Tells the view the reading position (whole-book), e.g. while speaking: contents, progress, saved position. */
    internal fun showPosition(offset: Int) = setPosition(offset)

    /** Puts [node] over the page, below the loading text; the caller sets alignment and margin. */
    internal fun addPageOverlay(node: Node) = root.children.add(root.children.size - 1, node)

    /** Adds a filter for the key events of the whole view; [handler] returns true to consume the event. */
    internal fun addKeyFilter(handler: (KeyEvent) -> Boolean) {
        root.addEventFilter(KeyEvent.ANY) { event -> if (handler(event)) event.consume() }
    }

    // --- FileView ---------------------------------------------------------------------------------------------

    /** For tests: the Contents panel, the toolbar and the notices. */
    internal val contents: ContentsPanel get() = contentsPanel
    internal val bar: EbookToolbar get() = toolbar
    internal val fixedLayoutBar: Node get() = noticeBar
    internal val overlayLabel: Label get() = overlayText
    internal val overlayShown: Boolean get() = overlay.isVisible
    internal val splitPane: SplitPane get() = split.node
    internal val topOffsetWhole: Int? get() = if (pageReady) topWhole() else null
    internal val currentPosition: Int get() = readingPosition

    @Throws(IOException::class)
    override fun reload() {
        if (disposed) return
        val changed = stampOf()
        if (loaded == null) {
            // Nothing is shown yet (still loading, or the first open failed): read it as the first open.
            stamp = changed
            startLoad(null, firstOpen = true)
            return
        }
        if (changed == stamp) return
        stamp = changed
        startLoad(if (pageReady) topWhole() ?: readingPosition else readingPosition, firstOpen = false)
    }

    /** Follows the book to [to] (the explorer moved it): the position is saved under the new name from now on. */
    override fun moved(to: Path): Boolean {
        if (disposed || to.extension.lowercase() != "epub") return false
        // No flush: the data was re-keyed to the new name already, so a pending offset has to be written there, by the
        // writer, which reads the file when it runs.
        location.file = to
        return true
    }

    override fun focusText() = webView.requestFocus()

    override fun focus() = pendingFocus.request(root) { if (!disposed) focusText() }

    override fun dispose() {
        if (disposed) return
        onDispose.toList().forEach { it() }
        if (scrollTimer.status == Animation.Status.RUNNING) recompute(follow = false)
        disposed = true
        loadGeneration++
        pageGeneration++
        pendingFocus.cancel()
        scrollTimer.stop()
        loadExecutor.shutdownNow()
        position.close()
        engine.loadContent("")
    }

    private companion object {
        const val START = "start"
    }
}
