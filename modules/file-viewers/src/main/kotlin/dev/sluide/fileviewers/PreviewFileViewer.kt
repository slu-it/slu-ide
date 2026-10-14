package dev.sluide.fileviewers

import javafx.application.ColorScheme
import javafx.application.Platform
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.ListChangeListener
import javafx.concurrent.Worker
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.SplitPane
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import netscape.javascript.JSObject
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
    /** Shows [text], the content of [file], in [engine]; the page is to use dark colours if [dark]. */
    fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
    )

    /** Whether the page has `sluide.scrollToLine` and blocks with `data-line`, so it can scroll with the source. */
    val syncsScroll: Boolean get() = false

    /** Whether the page has `sluide.setDark` and follows the colour scheme. */
    val themed: Boolean get() = true

    /** What a newly opened view shows. */
    val defaultMode: PreviewMode get() = PreviewMode.SPLIT
}

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
 * ([resolveLink]): web pages to the browser, files to SluIde. Should the WebView still end up on another page (e.g.
 * something dropped onto it), it renders the Markdown again; only its own page gets the link bridge.
 */
internal class PreviewFileView(
    private val file: Path,
    highlighter: SyntaxHighlighter,
    private val preview: Preview,
) : FileView {
    val source = CodeView(file, highlighter)
    val webView = WebView().apply { isContextMenuEnabled = false }
    val mode: ObjectProperty<PreviewMode> = SimpleObjectProperty(preview.defaultMode)

    private val split = SplitPane()
    private val modes = ToggleGroup()
    private val pane =
        BorderPane(split).apply {
            styleClass += "markdown-view"
            stylesheets += checkNotNull(PreviewFileView::class.java.getResource("preview-view.css")).toExternalForm()
        }
    private val engine = webView.engine
    override var host: FileViewHost? = null

    /** Receives the page's link clicks; held here because the WebView only keeps a weak reference. */
    private val bridge = LinkBridge()

    private var renderedText: String? = null
    private var dark = false
    private var syncPending = false

    /** Scroll position of the preview to restore after rendering, when the preview is shown alone. */
    private var previewScrollY = 0.0

    /** Where the divider was when the view last showed both sides. */
    private var dividerPosition = 0.5

    init {
        val buttons =
            PreviewMode.entries.map { mode ->
                ToggleButton(mode.label).apply {
                    toggleGroup = modes
                    userData = mode
                    styleClass += "markdown-mode"
                }
            }
        pane.top =
            HBox(*buttons.toTypedArray()).apply {
                styleClass += "markdown-toolbar"
                alignment = Pos.CENTER_RIGHT
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
        pane
            .sceneProperty()
            .flatMap { it.preferences.colorSchemeProperty() }
            .orElse(ColorScheme.LIGHT)
            .subscribe { scheme ->
                dark = scheme == ColorScheme.DARK
                applyTheme()
            }
        render()
    }

    override val node: Node get() = pane

    override fun reload() {
        source.reload()
        render()
    }

    private fun show(mode: PreviewMode) {
        if (split.items.size == 2) dividerPosition = split.dividerPositions[0]
        when (mode) {
            PreviewMode.SOURCE -> {
                split.items.setAll(source.node)
            }

            PreviewMode.SPLIT -> {
                split.items.setAll(source.node, webView)
                split.setDividerPositions(dividerPosition)
            }

            PreviewMode.PREVIEW -> {
                split.items.setAll(webView)
            }
        }
    }

    /** Renders the source text into the preview, unless it shows that text already. */
    private fun render() {
        val text = source.area.text
        if (text == renderedText) return
        renderedText = text
        if (isLoaded) previewScrollY = (engine.executeScript("window.scrollY") as Number).toDouble()
        preview.load(engine, file.toAbsolutePath(), text, dark)
    }

    /** Whether the preview shows our rendered page: loaded from a string, it has no location of its own. */
    private val isLoaded
        get() =
            engine.loadWorker.state == Worker.State.SUCCEEDED &&
                engine.location.let { it.isNullOrEmpty() || it == "about:blank" }

    private fun onLoaded() {
        if (!isLoaded) {
            // Navigated away from our page: show the Markdown again.
            renderedText = null
            render()
            return
        }
        (engine.executeScript("window") as JSObject).setMember("sluideBridge", bridge)
        applyTheme()
        if (!preview.syncsScroll || mode.get() == PreviewMode.PREVIEW) {
            engine.executeScript("window.scrollTo(0, $previewScrollY)")
        } else {
            syncScroll()
        }
    }

    private fun applyTheme() {
        if (isLoaded && preview.themed) engine.executeScript("sluide.setDark($dark)")
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
        fun open(href: String) {
            when (val target = resolveLink(href, file.toAbsolutePath().parent)) {
                is LinkTarget.WebPage -> {
                    host?.openWebPage(target.uri)
                }

                is LinkTarget.File -> {
                    host?.openFile(target.path)
                }

                null -> {}
            }
        }
    }
}
