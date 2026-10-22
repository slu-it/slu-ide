package dev.sluide.previews.preview

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.fileviewers.CodeEditor
import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FocusWhenShown
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TextDiff
import dev.sluide.textediting.CommentSyntax
import javafx.application.ColorScheme
import javafx.application.Platform
import javafx.beans.property.BooleanProperty
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.concurrent.Worker
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.ToggleGroup
import javafx.scene.input.DragEvent
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.web.WebView
import netscape.javascript.JSObject
import java.io.IOException
import java.nio.file.Path

/**
 * [file] as highlighted, editable source ([CodeEditor]) and a rendered [preview] in a `WebView`, side by side or one
 * of them alone ([mode], chosen in a toolbar). The preview shows the saved text: it follows the window's colour scheme
 * and is rendered again when the file is saved or changes on disk. Editing and saving are the source's.
 *
 * If the [preview] [syncs scroll][Preview.syncsScroll], side by side the preview scrolls with the source: the source
 * line at the top of the source is brought to the top of the preview (`sluide.scrollToLine`, interpolating between
 * the blocks' `data-line`). After a reload the preview goes back to where it was: to the source's line side by side,
 * to its old scroll position otherwise.
 *
 * Clicked links never navigate the preview: anchors scroll within it, every other link goes to [host]
 * ([resolveLink]): web pages to the browser, files to Thoreau. Pages without our script (e.g. HTML files) do navigate:
 * a navigation away from the [own page][Preview.isOwnPage] within 2 seconds of a mouse press, key press or drop in
 * the preview is the user's, so its target goes to [host] and the page is shown again. Any other navigation (a meta
 * refresh, a script setting `location`), and the page loading itself again without such input, stops the preview
 * with a notice until the user shows the page again or the file changes, so a page can neither open things on its
 * own nor reload in a loop. A page that cannot be loaded (e.g. no server) stops with a notice too. Only the own page
 * gets the bridge ([PageBridge]).
 *
 * Pages that [offer scripts][Preview.offersScripts] run them only while [scripts] is on, after [confirmScripts]: such
 * a page's scripts can read the files of its [root] and send them to the web.
 *
 * Previews with [headings][Preview.outline] (Markdown) have a table of contents ([toc]): a toggle on the left of the
 * toolbar opens an overlay over the top left of the view that lists the headings, selects the section of the caret (of
 * the preview's top line while only the preview shows) and jumps to the chosen heading in the source and the preview.
 *
 * Images and diagrams that [zoom][Preview.zooms] have the [zoom] controls on the left of the toolbar while the preview
 * shows; the page keeps the zoom when it is rendered again.
 *
 * Previews that [fold][Preview.folds] have the [folds] buttons (Collapse All, Expand All) on the left of the toolbar
 * while the preview shows. If they also [copy their text formatted][Preview.copiesFormatted], the Copy Formatted button
 * follows them.
 */
internal class PreviewFileView(
    file: Path,
    highlighter: SyntaxHighlighter,
    private val preview: Preview,
    comment: CommentSyntax = CommentSyntax.NONE,
) : EditableFileView {
    init {
        // They would share the left of the toolbar, and pages that run their own scripts have no zoom of ours.
        require(!(preview.offersScripts && preview.zooms)) { "A preview cannot both offer scripts and zoom" }
        require(!(preview.hasOutline && (preview.offersScripts || preview.zooms))) {
            "A preview with an outline cannot offer scripts or zoom"
        }
        require(!(preview.folds && (preview.offersScripts || preview.zooms || preview.hasOutline))) {
            "A preview that folds cannot offer scripts, zoom or have an outline"
        }
        // The Copy Formatted button sits beside the fold buttons.
        require(!preview.copiesFormatted || preview.folds) { "A preview that copies its text formatted must fold" }
    }

    /** The file shown; it changes when the file is [moved]. */
    private var file = file.toAbsolutePath().normalize()

    val source: CodeEditor = CodeEditors.open(file, highlighter, comment.line, comment.block)
    val webView = WebView().apply { isContextMenuEnabled = false }
    val mode: ObjectProperty<PreviewMode> = SimpleObjectProperty(preview.defaultMode)

    /** Whether the page's own scripts run, if the preview [offers that][Preview.offersScripts]. */
    val scripts: BooleanProperty = SimpleBooleanProperty(false)

    /** Asks whether to run the page's scripts after a warning; tests replace it, since it blocks. */
    var confirmScripts: () -> Boolean = { showScriptsWarning(root, file, pane.scene?.window) }

    /** Shown over the preview when the page navigated on its own; see [stop]. */
    val notice = Label().apply { isWrapText = true }
    private val noticePane =
        VBox(
            notice,
            Button("Show the page again").apply { setOnAction { resume() } },
        ).apply {
            styleClass += "preview-notice"
            isVisible = false
        }

    /** The preview side: the web view, with [notice] over it when stopped. */
    val previewPane = StackPane(webView, noticePane)

    private val split = SplitPane()
    private val modes = ToggleGroup()

    /** The table of contents, if the preview has [headings][Preview.outline]. */
    val toc: TableOfContents? =
        if (preview.hasOutline) {
            TableOfContents(
                headings = { preview.outline(source.text).orEmpty() },
                currentLine = ::currentLine,
                navigate = ::navigateTo,
                restoreFocus = ::focusText,
            )
        } else {
            null
        }

    /** The Export button, if the preview [can be exported][Preview.exports]. */
    val export: ExportControls? =
        preview.exports.takeIf { it.isNotEmpty() }?.let {
            ExportControls(it, { file }, { source.savedText }, { pane.scene?.window }) {
                host?.windowService(BackgroundTasks::class.java)
            }
        }

    private val pane =
        BorderPane(if (toc != null) StackPane(split, toc.overlay) else split).apply {
            styleClass += "preview-view"
            stylesheets +=
                checkNotNull(
                    PreviewFileView::class.java.getResource("/dev/sluide/previews/preview-view.css"),
                ).toExternalForm()
        }
    private val engine = webView.engine

    /** The zoom of an image or diagram, if the preview [zooms][Preview.zooms]. */
    val zoom: ZoomControls? = if (preview.zooms) ZoomControls(engine) { isLoaded } else null

    /** The Collapse All and Expand All buttons, if the preview [folds][Preview.folds]. */
    val folds: FoldControls? = if (preview.folds) FoldControls(engine) { isLoaded } else null

    /** The Copy Formatted button, if the preview [copies its text formatted][Preview.copiesFormatted]. */
    val copyFormatted =
        if (preview.copiesFormatted) CopyFormattedControls({ preview.formatted(source.savedText) }) else null

    /** Its [project folder][FileViewHost.projectFolder] gives the page its [root]. */
    override var host: FileViewHost? = null
        set(value) {
            val oldRoot = root
            field = value
            // Clicking a change marker of the source asks the host for the diff.
            source.host = value
            // A stopped preview renders with the new root when it resumes.
            if (preview.usesRoot && renderedText != null && !stopped && root != oldRoot) rerender()
        }

    /** The folder whose files the page may use ([Preview.root]). */
    private val root: Path get() = preview.root(file, host?.projectFolder?.toAbsolutePath()?.normalize())

    /** Receives the page's link clicks and zoom changes; held here because the WebView only keeps a weak reference. */
    private val bridge = PageBridge(::openLink) { scale, fitted -> zoom?.zoomed(scale, fitted) }

    private var renderedText: String? = null
    private var dark = false
    private var syncPending = false
    private var tocPending = false

    /** The last jump of the table of contents in the preview; forgotten when the page is rendered again. */
    private var tocJump: TocJump? = null

    /** The [line] a jump went to, and the [page] right after it: the line holds only while the page looks the same. */
    private class TocJump(
        val line: Int,
        val page: String,
    )

    /** Scroll position of the preview to restore after rendering, when the preview is shown alone. */
    private var previewScrollY = 0.0

    /** Where the divider was when the view last showed both sides. */
    private var dividerPosition = 0.5

    /** When the user last pressed a mouse button or key in the preview, or dropped something onto it. */
    private var lastInput: Long? = null

    /** Whether the preview stopped after the page navigated on its own ([stop]). */
    private var stopped = false

    /** Whether the view was [disposed][dispose]: it stays stopped for good. */
    private var disposed = false

    /** Focuses the source or the preview once it is shown ([focus]). */
    private val pendingFocus = FocusWhenShown()

    /** Whether the view started a load that has not finished yet; a load it did not start is the page's own doing. */
    private var loading = false

    private val toolbar =
        PreviewToolbar(preview, modes, mode, scripts, toc, export, zoom, folds, copyFormatted, webView)
    private val buttons = toolbar.buttons

    init {
        pane.top = toolbar.node
        engine.isJavaScriptEnabled = !preview.offersScripts
        scripts.addListener { _, _, allowed ->
            // Also the toggle going back off after the warning was cancelled: nothing changed, nothing to render.
            if (!preview.offersScripts || engine.isJavaScriptEnabled == allowed) return@addListener
            if (allowed && !confirmScripts()) {
                Platform.runLater { scripts.set(false) }
                return@addListener
            }
            engine.isJavaScriptEnabled = allowed
            rerender()
        }
        for (type in listOf(MouseEvent.MOUSE_PRESSED, KeyEvent.KEY_PRESSED, DragEvent.DRAG_DROPPED)) {
            webView.addEventFilter(type) { lastInput = System.nanoTime() }
        }
        if (toc != null) {
            // A press anywhere in the view, also in the web view, outside the table closes it.
            pane.addEventFilter(MouseEvent.MOUSE_PRESSED, ::closeTocOnPress)
            source.caretLineProperty.subscribe { _ -> refreshToc() }
            source.onTextChanged { refreshToc() }
        }
        modes.selectedToggleProperty().subscribe { old, new ->
            // A mode is always selected: clicking the selected button keeps it.
            if (new == null) modes.selectToggle(old) else mode.set(new.userData as PreviewMode)
        }
        mode.subscribe { mode ->
            modes.selectToggle(buttons.first { it.userData == mode })
            show(mode)
            syncScroll()
        }
        source.onShownLinesChanged { syncScroll() }
        // Saved, reloaded, or the user kept the unsaved text over the file's new one, which then counts as saved.
        source.savedTextProperty.addListener { _, _, _ -> if (!disposed) render() }
        engine.loadWorker.stateProperty().subscribe { state -> if (state == Worker.State.SUCCEEDED) onLoaded() }
        engine.locationProperty().subscribe { location ->
            if (!location.isNullOrEmpty() && !preview.isOwnPage(location, this.file)) navigatedAway(location)
        }
        pane
            .sceneProperty()
            .flatMap { it.preferences.colorSchemeProperty() }
            .orElse(ColorScheme.LIGHT)
            .subscribe { scheme ->
                dark = scheme == ColorScheme.DARK
                applyTheme()
            }
        // Whoever shows the view sets its host, and with it the root, right after creating it; loading the page
        // before would serve it with the wrong files.
        if (preview.usesRoot) Platform.runLater(::render) else render()
    }

    override val node: Node get() = pane

    override val dirty get() = source.dirty

    override fun save() {
        source.save()
        reload()
    }

    override fun showChanges(diff: TextDiff?) = source.showChanges(diff)

    /** Focuses the source, or the preview while it is shown alone. */
    override fun focusText() = if (mode.get() == PreviewMode.PREVIEW) webView.requestFocus() else source.focusText()

    /**
     * Focuses the preview while it is shown alone, and a zooming one (a diagram) while it shows, so that the zoom keys
     * work at once; otherwise the source, also of a stopped zooming preview in Split. A stopped preview shown alone
     * does not take the focus.
     */
    override fun focus() {
        val mode = mode.get()
        if (mode == PreviewMode.PREVIEW || (mode == PreviewMode.SPLIT && preview.zooms && !stopped)) {
            pendingFocus.request(webView) {
                if (!disposed && !stopped && this.mode.get() != PreviewMode.SOURCE) webView.requestFocus()
            }
        } else {
            pendingFocus.request(source.node) { if (!disposed) source.focusText() }
        }
    }

    override fun reload() {
        if (disposed) return
        source.reload()
        if (stopped && source.savedText != renderedText) resume() else render()
    }

    /** Reads and saves the file at [to] from now on, keeping the source's text and unsaved changes, and renders it there. */
    override fun moved(to: Path): Boolean {
        // While the page is still the old file's own one; for HTML, it is not the new file's.
        rememberScroll()
        file = to.toAbsolutePath().normalize()
        source.moved(file)
        // The page depends on where the file is: relative links, and for HTML the address it is served at.
        if (!disposed && !stopped) rerender()
        return true
    }

    /** Stops the page for good: a disposed view never renders again, not even on [reload] or [resume]. */
    override fun dispose() {
        disposed = true
        stopped = true
        pendingFocus.cancel()
        copyFormatted?.clear()
        toc?.close()
        engine.load("")
        source.dispose()
    }

    private fun show(mode: PreviewMode) {
        if (split.items.size == 2) dividerPosition = split.dividerPositions[0]
        when (mode) {
            PreviewMode.SOURCE -> {
                split.items.setAll(source.node)
            }

            PreviewMode.SPLIT -> {
                split.items.setAll(source.node, previewPane)
                split.setDividerPositions(dividerPosition)
            }

            PreviewMode.PREVIEW -> {
                split.items.setAll(previewPane)
            }
        }
    }

    /** Renders the saved source text into the preview, unless it shows that text already. */
    private fun render() {
        val text = source.savedText
        if (stopped || text == renderedText) return
        renderedText = text
        tocJump = null
        copyFormatted?.clear()
        rememberScroll()
        try {
            loading = true
            preview.load(engine, file, text, dark, root)
        } catch (e: IOException) {
            stop("The preview could not be shown: ${e.message}")
        }
    }

    /** Remembers where the page is scrolled to, if it is loaded, to scroll there again after rendering. */
    private fun rememberScroll() {
        // A page with scripts may redefine scrollY.
        if (isLoaded) previewScrollY = (engine.executeScript("window.scrollY") as? Number)?.toDouble() ?: 0.0
    }

    /** Renders the preview again even though the text did not change. */
    private fun rerender() {
        renderedText = null
        render()
    }

    /** Whether the preview shows its own page, completely loaded; a stopped one shows a blank page. */
    private val isLoaded
        get() =
            !stopped && engine.loadWorker.state == Worker.State.SUCCEEDED && preview.isOwnPage(engine.location, file)

    /**
     * The preview left its page. Right after the user's input (e.g. a link clicked in an HTML file) the target opens
     * elsewhere and the page comes back; otherwise the page navigated on its own and the preview [stops][stop].
     */
    private fun navigatedAway(location: String) {
        if (stopped) return
        if (isUserInput()) {
            openLink(location)
            Platform.runLater(::rerender)
        } else {
            stop("The page tried to go to $location on its own. It is not shown, so that it cannot keep doing so.")
        }
    }

    /** Shows a blank preview with a notice saying [why], until [resume]. */
    private fun stop(why: String) {
        stopped = true
        notice.text = why
        noticePane.isVisible = true
        Platform.runLater { engine.loadContent("") }
    }

    private fun resume() {
        if (disposed) return
        stopped = false
        noticePane.isVisible = false
        rerender()
    }

    /** Whether the user just pressed a mouse button or key in the preview, or dropped something; it counts once. */
    private fun isUserInput(): Boolean {
        val input = lastInput ?: return false
        lastInput = null
        return System.nanoTime() - input < USER_NAVIGATION_NANOS
    }

    private fun onLoaded() {
        if (stopped) return
        if (!isLoaded) {
            // Not our page (see navigatedAway): show ours again.
            rerender()
            return
        }
        val started = loading
        loading = false
        if (!started && !isUserInput()) {
            // The page loaded itself again (a meta refresh, `location.reload()`), and would keep doing so.
            stop("The page tried to reload itself. It is not shown, so that it cannot keep doing so.")
            return
        }
        if (preview.hasOwnScript) (engine.executeScript("window") as JSObject).setMember("sluideBridge", bridge)
        zoom?.applyTo()
        applyTheme()
        if (!preview.syncsScroll || mode.get() == PreviewMode.PREVIEW) {
            engine.executeScript("window.scrollTo(0, $previewScrollY)")
        } else {
            syncScroll()
        }
    }

    private fun applyTheme() {
        if (isLoaded && preview.hasOwnScript) engine.executeScript("sluide.setDark($dark)")
    }

    /** Opens what [href] leads to (see [resolveLink]) through the [host]. */
    private fun openLink(href: String) {
        when (val target = preview.linkTarget(href, file)) {
            is LinkTarget.WebPage -> {
                host?.openWebPage(target.uri)
            }

            is LinkTarget.File -> {
                host?.openFile(target.path)
            }

            null -> {}
        }
    }

    /** The source line the table of contents starts from: the caret's, or the preview's top line if the source is hidden. */
    private fun currentLine(): Int {
        if (mode.get() == PreviewMode.PREVIEW && isLoaded) {
            // The page cannot always scroll a heading near its end to the top: where we put it is still its line.
            tocJump?.let { if (pageState() == it.page) return it.line }
            (engine.executeScript("sluide.topLine()") as? Number)?.let { return it.toInt() }
        }
        return source.caretLine
    }

    /** Updates the open table of contents to the caret and text; calls before it ran are merged into one. */
    private fun refreshToc() {
        if (tocPending || toc?.isOpen != true || mode.get() == PreviewMode.PREVIEW) return
        tocPending = true
        Platform.runLater {
            tocPending = false
            if (!disposed && mode.get() != PreviewMode.PREVIEW) toc?.refresh()
        }
    }

    /** Scroll position, height and width of the page; the same values mean the same layout. */
    private fun pageState(): String? =
        engine
            .executeScript("[window.scrollY, document.documentElement.scrollHeight, window.innerWidth].join(',')")
            as? String

    /**
     * A press in the view closes the table of contents unless it is in the table or its button. Afterwards, if the
     * focus is nowhere or in the closed table or in a view that is gone (a toolbar button or a scroll bar was pressed,
     * and neither takes focus), it goes back to the source or the preview.
     */
    private fun closeTocOnPress(event: MouseEvent) {
        val toc = toc ?: return
        if (!toc.isOpen || toc.contains(event.target)) return
        toc.close()
        Platform.runLater {
            val owner = pane.scene?.focusOwner
            if (!disposed && (owner == null || owner === toc.list || owner.scene == null || !owner.isVisible)) {
                focusText()
            }
        }
    }

    /** Brings [heading] to the top of the source, and of the preview if it is shown alone (else the scroll sync does). */
    private fun navigateTo(heading: Heading) {
        source.showLineAtTop(heading.line)
        val line = source.caretLine
        if (mode.get() == PreviewMode.PREVIEW && isLoaded) {
            engine.executeScript("sluide.scrollToLine($line)")
            tocJump = pageState()?.let { TocJump(line, it) }
        }
        focusText()
    }

    /** Side by side, scrolls the preview to the source's top line; calls before it ran are merged into one. */
    private fun syncScroll() {
        if (syncPending) return
        syncPending = true
        Platform.runLater {
            syncPending = false
            val top = source.topLine
            if (preview.syncsScroll && mode.get() == PreviewMode.SPLIT && isLoaded && top >= 0) {
                engine.executeScript("sluide.scrollToLine($top)")
            }
        }
    }
}

/** How soon after the user's input a navigation counts as the user's: following a link takes a moment. */
private const val USER_NAVIGATION_NANOS = 2_000_000_000L
