package dev.sluide.fileviewers

import javafx.application.ColorScheme
import javafx.application.Platform
import javafx.beans.property.BooleanProperty
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.ListChangeListener
import javafx.concurrent.Worker
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.input.DragEvent
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import netscape.javascript.JSObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Read-only views of text files with one of the given [extensions] (case-insensitive) as highlighted source beside a
 * rendered [preview] ([PreviewFileView], ADR 0016); see `MarkdownFileViewer`.
 */
abstract class PreviewFileViewer internal constructor(
    private val extensions: Set<String>,
    internal val highlighter: SyntaxHighlighter,
    private val preview: Preview,
) : FileViewer {
    override fun canOpen(file: Path): Boolean =
        file.extension.lowercase() in extensions && Files.isReadable(file) && isTextFile(file)

    override fun open(file: Path): FileView = PreviewFileView(file, highlighter, preview)
}

/** How a [PreviewFileView] renders a file. */
internal interface Preview {
    /**
     * Shows [text], the content of [file], in [engine]; the page is to use dark colours if [dark] and may use the files
     * in [root] (see [root]).
     */
    @Throws(IOException::class)
    fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    )

    /** Whether the page has `sluide.scrollToLine` and blocks with `data-line`, so it can scroll with the source. */
    val syncsScroll: Boolean get() = false

    /**
     * Whether the page brings our script (`preview.js`): it follows the colour scheme (`sluide.setDark`) and hands
     * link clicks to the view.
     */
    val hasOwnScript: Boolean get() = true

    /** Whether [load] uses its `root`, so that the page has to be loaded again when the root changes. */
    val usesRoot: Boolean get() = false

    /** The folder whose files the page for [file] may use, given the [projectFolder] the view belongs to. */
    fun root(
        file: Path,
        projectFolder: Path?,
    ): Path = projectFolder?.takeIf { file.startsWith(it) } ?: file.parent

    /** Whether the page's own scripts may run when the user allows it (a toolbar toggle, off by default). */
    val offersScripts: Boolean get() = false

    /**
     * Whether the preview at [location] still shows the page for [file]; anything else is a navigation away, which
     * the view turns back. Pages loaded from a string have no location.
     */
    fun isOwnPage(
        location: String?,
        file: Path,
    ): Boolean = isPageFromString(location)

    /** Where [href], a link clicked or followed in the page for [file], leads. */
    fun linkTarget(
        href: String,
        file: Path,
    ): LinkTarget? = resolveLink(href, file.parent)

    /** What a newly opened view shows. */
    val defaultMode: PreviewMode get() = PreviewMode.SPLIT
}

/** Whether a `WebView` at [location] shows a page loaded from a string (`loadContent`), which has no location. */
internal fun isPageFromString(location: String?): Boolean = location.isNullOrEmpty() || location == "about:blank"

/** What a [PreviewFileView] shows. */
internal enum class PreviewMode(
    val label: String,
) {
    SOURCE("Source"),
    SPLIT("Split"),
    PREVIEW("Preview"),
}

/**
 * [file] as highlighted source ([CodeView]) and a rendered [preview] in a `WebView`, side by side or one of them
 * alone ([mode], chosen in a toolbar). The preview follows the window's colour scheme and is rendered again when the
 * file changes.
 *
 * If the [preview] [syncs scroll][Preview.syncsScroll], side by side the preview scrolls with the source: the source
 * line at the top of the source is brought to the top of the preview (`sluide.scrollToLine`, interpolating between
 * the blocks' `data-line`). After a reload the preview goes back to where it was: to the source's line side by side,
 * to its old scroll position otherwise.
 *
 * Clicked links never navigate the preview: anchors scroll within it, every other link goes to [host]
 * ([resolveLink]): web pages to the browser, files to SluIde. Pages without our script (e.g. HTML files) do navigate:
 * a navigation away from the [own page][Preview.isOwnPage] right after a click or key press in the preview is the
 * user's, so its target goes to [host] and the page is shown again. Any other navigation (a meta refresh, a script
 * setting `location`), and the page loading itself again without such input, stops the preview with a notice until
 * the user shows the page again or the file changes, so a page can neither open things on its own nor reload in a
 * loop. A page that cannot be loaded (e.g. no server) stops with a notice too. Only the own page gets the link bridge.
 *
 * Pages that [offer scripts][Preview.offersScripts] run them only while [scripts] is on, after [confirmScripts]: such
 * a page's scripts can read the files of its [root] and send them to the web.
 */
internal class PreviewFileView(
    file: Path,
    highlighter: SyntaxHighlighter,
    private val preview: Preview,
) : FileView {
    private val file = file.toAbsolutePath().normalize()

    val source = CodeView(file, highlighter)
    val webView = WebView().apply { isContextMenuEnabled = false }
    val mode: ObjectProperty<PreviewMode> = SimpleObjectProperty(preview.defaultMode)

    /** Whether the page's own scripts run, if the preview [offers that][Preview.offersScripts]. */
    val scripts: BooleanProperty = SimpleBooleanProperty(false)

    /** Asks whether to run the page's scripts after a warning; tests replace it, since it blocks. */
    var confirmScripts: () -> Boolean = ::showScriptsWarning

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
    private val pane =
        BorderPane(split).apply {
            styleClass += "preview-view"
            stylesheets += checkNotNull(PreviewFileView::class.java.getResource("preview-view.css")).toExternalForm()
        }
    private val engine = webView.engine

    /** Its [project folder][FileViewHost.projectFolder] gives the page its [root]. */
    override var host: FileViewHost? = null
        set(value) {
            val oldRoot = root
            field = value
            // A stopped preview renders with the new root when it resumes.
            if (preview.usesRoot && renderedText != null && !stopped && root != oldRoot) rerender()
        }

    /** The folder whose files the page may use ([Preview.root]). */
    private val root: Path get() = preview.root(file, host?.projectFolder?.toAbsolutePath()?.normalize())

    /** Receives the page's link clicks; held here because the WebView only keeps a weak reference. */
    private val bridge = LinkBridge()

    private var renderedText: String? = null
    private var dark = false
    private var syncPending = false

    /** Scroll position of the preview to restore after rendering, when the preview is shown alone. */
    private var previewScrollY = 0.0

    /** Where the divider was when the view last showed both sides. */
    private var dividerPosition = 0.5

    /** When the user last pressed a mouse button or key in the preview, or dropped something onto it. */
    private var lastInput: Long? = null

    /** Whether the preview stopped after the page navigated on its own ([stop]). */
    private var stopped = false

    /** Whether the view started a load that has not finished yet; a load it did not start is the page's own doing. */
    private var loading = false

    init {
        val buttons =
            PreviewMode.entries.map { mode ->
                ToggleButton(mode.label).apply {
                    toggleGroup = modes
                    userData = mode
                    styleClass += "preview-mode"
                }
            }
        val extras =
            if (preview.offersScripts) {
                listOf(
                    ToggleButton("Scripts").apply {
                        styleClass += "preview-scripts"
                        tooltip = Tooltip("Run the page's scripts. They can read the files served with the page.")
                        selectedProperty().bindBidirectional(scripts)
                    },
                    Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                )
            } else {
                emptyList()
            }
        pane.top =
            HBox(*(extras + buttons).toTypedArray()).apply {
                styleClass += "preview-toolbar"
                alignment = Pos.CENTER_RIGHT
            }
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
        modes.selectedToggleProperty().subscribe { old, new ->
            // A mode is always selected: clicking the selected button keeps it.
            if (new == null) modes.selectToggle(old) else mode.set(new.userData as PreviewMode)
        }
        mode.subscribe { mode ->
            modes.selectToggle(buttons.first { it.userData == mode })
            show(mode)
            syncScroll()
        }
        // The visible paragraphs change when the source scrolls, is resized or is shown again (e.g. its tab selected).
        source.area.visibleParagraphs.addListener(ListChangeListener { syncScroll() })
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

    override fun reload() {
        source.reload()
        if (stopped && source.area.text != renderedText) resume() else render()
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

    /** Renders the source text into the preview, unless it shows that text already. */
    private fun render() {
        val text = source.area.text
        if (stopped || text == renderedText) return
        renderedText = text
        // A page with scripts may redefine scrollY.
        if (isLoaded) previewScrollY = (engine.executeScript("window.scrollY") as? Number)?.toDouble() ?: 0.0
        try {
            loading = true
            preview.load(engine, file, text, dark, root)
        } catch (e: IOException) {
            stop("The preview could not be shown: ${e.message}")
        }
    }

    /** Renders the preview again even though the text did not change. */
    private fun rerender() {
        renderedText = null
        render()
    }

    /** Whether the preview shows its own page, completely loaded. */
    private val isLoaded
        get() = engine.loadWorker.state == Worker.State.SUCCEEDED && preview.isOwnPage(engine.location, file)

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
        stopped = false
        noticePane.isVisible = false
        rerender()
    }

    private fun showScriptsWarning(): Boolean {
        val run = ButtonType("Run Scripts", ButtonBar.ButtonData.OK_DONE)
        val alert =
            Alert(
                Alert.AlertType.WARNING,
                "The page's scripts can read the files in $root (except hidden ones) and send them to the web. " +
                    "Run them only for pages you trust.",
                run,
                ButtonType.CANCEL,
            ).apply {
                pane.scene?.window?.let(::initOwner)
                title = "Run Scripts"
                headerText = "Run the scripts of ${file.fileName}?"
            }
        return alert.showAndWait().orElse(ButtonType.CANCEL) == run
    }

    /** Whether the user just pressed a mouse button or key in the preview; it counts once. */
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

    /** Side by side, scrolls the preview to the source's top line; calls before it ran are merged into one. */
    private fun syncScroll() {
        if (syncPending) return
        syncPending = true
        Platform.runLater {
            syncPending = false
            val area = source.area
            if (preview.syncsScroll && mode.get() == PreviewMode.SPLIT && isLoaded &&
                area.visibleParagraphs.isNotEmpty()
            ) {
                engine.executeScript("sluide.scrollToLine(${area.firstVisibleParToAllParIndex()})")
            }
        }
    }

    /** Called by the page's script (`sluideBridge.open(href)`) when a link is clicked. */
    inner class LinkBridge {
        fun open(href: String) = openLink(href)
    }
}

/** How soon after the user's input a navigation counts as the user's: following a link takes a moment. */
private const val USER_NAVIGATION_NANOS = 2_000_000_000L
