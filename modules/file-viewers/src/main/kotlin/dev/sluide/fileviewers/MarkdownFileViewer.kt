package dev.sluide.fileviewers

import javafx.application.ColorScheme
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
    private var renderedText: String? = null
    private var dark = false

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
        }
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
        engine.loadContent(MarkdownRenderer.render(text, file.toAbsolutePath().parent))
    }

    private fun onLoaded() {
        applyTheme()
    }

    private fun applyTheme() {
        if (engine.loadWorker.state == Worker.State.SUCCEEDED) engine.executeScript("sluide.setDark($dark)")
    }
}
