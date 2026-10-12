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
    private val highlighter: SyntaxHighlighter,
) : FileViewer {
    override fun canOpen(file: Path): Boolean =
        file.extension.lowercase() in extensions && Files.isReadable(file) && isTextFile(file)

    override fun open(file: Path): Node {
        val text = file.readText()
        val area =
            CodeArea(text).apply {
                isEditable = false
                paragraphGraphicFactory = LineNumberFactory.get(this)
                if (text.isNotEmpty()) setStyleSpans(0, styleSpans(text))
                moveTo(0)
            }
        return VirtualizedScrollPane(area).apply {
            styleClass += "code-view"
            stylesheets += checkNotNull(CodeFileViewer::class.java.getResource("syntax.css")).toExternalForm()
        }
    }

    private fun styleSpans(text: String): StyleSpans<Collection<String>> {
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
}
