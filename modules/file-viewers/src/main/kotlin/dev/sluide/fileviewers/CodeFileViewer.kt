package dev.sluide.fileviewers

import javafx.scene.Node
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.LineNumberFactory
import org.fxmisc.richtext.model.StyleSpans
import org.fxmisc.richtext.model.StyleSpansBuilder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Read-only, syntax-highlighted view of source files with one of the given [extensions] (case-insensitive).
 * Subclasses pick the language via [highlighter]; see `PythonFileViewer`.
 */
abstract class CodeFileViewer internal constructor(
    private val extensions: Set<String>,
    internal val highlighter: SyntaxHighlighter,
) : FileViewer {
    override fun canOpen(file: Path): Boolean =
        file.extension.lowercase() in extensions && Files.isReadable(file) && isTextFile(file)

    override fun open(file: Path): FileView = CodeView(file, highlighter)
}

/**
 * Read-only view of [file] as code highlighted by [highlighter], with line numbers. Used by the code viewers and as
 * the source side of other views, e.g. the Markdown preview.
 */
internal class CodeView(
    private val file: Path,
    private val highlighter: SyntaxHighlighter,
) : FileView {
    /** The RichTextFX area showing the text, for views that embed this one. */
    val area =
        CodeArea().apply {
            isEditable = false
            paragraphGraphicFactory = LineNumberFactory.get(this)
        }
    private val pane =
        VirtualizedScrollPane(area).apply {
            styleClass += "code-view"
            stylesheets += checkNotNull(CodeView::class.java.getResource("syntax.css")).toExternalForm()
        }

    init {
        show(file.readText())
        area.moveTo(0)
    }

    override val node: Node get() = pane

    override fun reload() {
        val text = file.readText()
        if (text == area.text) return
        val caret = area.caretPosition
        val scrollX = pane.estimatedScrollX
        val scrollY = pane.estimatedScrollY
        show(text)
        area.moveTo(caret.coerceAtMost(text.length))
        // Moving the caret makes the area follow it during the next layout; lay out now, then scroll back.
        pane.layout()
        pane.scrollXToPixel(scrollX)
        pane.scrollYToPixel(scrollY)
    }

    private fun show(text: String) {
        area.replaceText(text)
        if (text.isNotEmpty()) area.setStyleSpans(0, codeStyleSpans(text, highlighter))
    }
}

/** The token style classes of [text] found by [highlighter], for a RichTextFX area. */
internal fun codeStyleSpans(
    text: String,
    highlighter: SyntaxHighlighter,
): StyleSpans<Collection<String>> {
    val builder = StyleSpansBuilder<Collection<String>>()
    var position = 0
    for (span in highlighter.highlight(text)) {
        builder.add(emptyList(), span.start - position)
        builder.add(listOf(span.style.styleClass), span.end - span.start)
        position = span.end
    }
    builder.add(emptyList(), text.length - position)
    return builder.create()
}
