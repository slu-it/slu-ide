package dev.sluide.fileviewers

import javafx.application.ColorScheme
import javafx.application.Platform
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.concurrent.Worker
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.SplitPane
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.web.WebView
import netscape.javascript.JSObject
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Shows Markdown documents (`.md`, `.markdown`) as highlighted source beside a rendered preview (ADR 0016); see
 * [MarkdownFileView].
 */
@Component
class MarkdownFileViewer : FileViewer {
    internal val highlighter: SyntaxHighlighter = RSyntaxHighlighter.markdown

    override fun canOpen(file: Path): Boolean =
        file.extension.lowercase() in setOf("md", "markdown") && Files.isReadable(file) && isTextFile(file)

    override fun open(file: Path): FileView = MarkdownFileView(file, highlighter)
}

/** What a [MarkdownFileView] shows. */
internal enum class MarkdownMode(
    val label: String,
) {
    SOURCE("Source"),
    SPLIT("Split"),
    PREVIEW("Preview"),
}

/**
 * Markdown [file] as highlighted source ([CodeView]) and a rendered preview ([MarkdownRenderer] in a `WebView`),
 * side by side or one of them alone ([mode], chosen in a toolbar). The preview follows the window's colour scheme
 * and is rendered again when the file changes.
 *
 * Side by side, the preview scrolls with the source: the source line at the top of the source is brought to the top
 * of the preview (`sluide.scrollToLine`, interpolating between the blocks' `data-line`). After a reload the preview
 * goes back to where it was: to the source's line side by side, to its old scroll position when shown alone.
 *
 * Clicked links never navigate the preview: anchors scroll within it, every other link goes to [host]
 * ([resolveLink]): web pages to the browser, files to SluIde.
 */
internal class MarkdownFileView(
    private val file: Path,
    highlighter: SyntaxHighlighter,
) : FileView {
    val source = CodeView(file, highlighter)
    val preview = WebView().apply { isContextMenuEnabled = false }
    val mode: ObjectProperty<MarkdownMode> = SimpleObjectProperty(MarkdownMode.SPLIT)

    private val split = SplitPane()
    private val modes = ToggleGroup()
    private val pane =
        BorderPane(split).apply {
            styleClass += "markdown-view"
            stylesheets += checkNotNull(MarkdownFileView::class.java.getResource("markdown-view.css")).toExternalForm()
        }
    private val engine = preview.engine
    override var host: FileViewHost? = null

    /** Receives the page's link clicks; held here because the WebView only keeps a weak reference. */
    private val bridge = LinkBridge()

    private var renderedText: String? = null
    private var dark = false
    private var syncPending = false

    /** Scroll position of the preview to restore after rendering, when the preview is shown alone. */
    private var previewScrollY = 0.0

    init {
        val buttons =
            MarkdownMode.entries.map { mode ->
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
            if (new == null) modes.selectToggle(old) else mode.set(new.userData as MarkdownMode)
        }
        mode.subscribe { mode ->
            modes.selectToggle(buttons.first { it.userData == mode })
            show(mode)
            syncScroll()
        }
        source.area.estimatedScrollYProperty().addListener { _, _, _ -> syncScroll() }
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

    private fun show(mode: MarkdownMode) {
        when (mode) {
            MarkdownMode.SOURCE -> {
                split.items.setAll(source.node)
            }

            MarkdownMode.SPLIT -> {
                split.items.setAll(source.node, preview)
                split.setDividerPositions(0.5)
            }

            MarkdownMode.PREVIEW -> {
                split.items.setAll(preview)
            }
        }
    }

    /** Renders the source text into the preview, unless it shows that text already. */
    private fun render() {
        val text = source.area.text
        if (text == renderedText) return
        renderedText = text
        if (isLoaded) previewScrollY = (engine.executeScript("window.scrollY") as Number).toDouble()
        engine.loadContent(MarkdownRenderer.render(text, file.toAbsolutePath().parent))
    }

    private val isLoaded get() = engine.loadWorker.state == Worker.State.SUCCEEDED

    private fun onLoaded() {
        (engine.executeScript("window") as JSObject).setMember("sluideBridge", bridge)
        applyTheme()
        if (mode.get() == MarkdownMode.PREVIEW) {
            engine.executeScript("window.scrollTo(0, $previewScrollY)")
        } else {
            syncScroll()
        }
    }

    private fun applyTheme() {
        if (isLoaded) engine.executeScript("sluide.setDark($dark)")
    }

    /** Side by side, scrolls the preview to the source's top line, at most once per pulse. */
    private fun syncScroll() {
        if (syncPending) return
        syncPending = true
        Platform.runLater {
            syncPending = false
            val area = source.area
            if (mode.get() == MarkdownMode.SPLIT && isLoaded && area.visibleParagraphs.isNotEmpty()) {
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
