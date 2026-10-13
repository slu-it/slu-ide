package dev.sluide.fileviewers

import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.layout.BorderPane
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.model.StyleSpans
import org.fxmisc.richtext.model.StyleSpansBuilder
import java.io.IOException
import java.nio.file.Path
import java.util.function.IntFunction
import kotlin.io.path.readText

/** A file shown side by side with an original version of it, the differences coloured (ADR 0015). */
interface DiffFileView : FileView {
    /** Reads the file again and shows it against [diff], keeping the scroll position. */
    @Throws(IOException::class)
    fun show(diff: TextDiff)
}

/**
 * Read-only side-by-side view: the original on the left, the file on the right, with filler rows so that both sides
 * have the same rows and scroll together. Removed, modified and added lines get the [DiffKind] style classes on their
 * paragraph and line number. Text is highlighted with [highlighter] if there is one.
 */
internal class SideBySideDiffView(
    private val file: Path,
    private val highlighter: SyntaxHighlighter?,
    private var diff: TextDiff,
) : DiffFileView {
    private val left = Side()
    private val right = Side()

    private val split =
        SplitPane(left.box, right.box).apply {
            styleClass += "diff-view"
            stylesheets += checkNotNull(SideBySideDiffView::class.java.getResource("syntax.css")).toExternalForm()
        }

    init {
        // Both sides have the same rows, so the same scroll position shows the same rows.
        left.area.estimatedScrollYProperty().bindBidirectional(right.area.estimatedScrollYProperty())
        left.area.estimatedScrollXProperty().bindBidirectional(right.area.estimatedScrollXProperty())
        render(file.readText())
        left.area.moveTo(0)
        right.area.moveTo(0)
    }

    override val node: Node get() = split

    override fun reload() = show(diff)

    override fun show(diff: TextDiff) {
        val text = file.readText()
        val scrollX = right.pane.estimatedScrollX
        val scrollY = right.pane.estimatedScrollY
        this.diff = diff
        render(text)
        // Replacing the text moves the view; lay out now, then scroll back.
        split.layout()
        right.pane.scrollXToPixel(scrollX)
        right.pane.scrollYToPixel(scrollY)
    }

    private fun render(text: String) {
        val originalLines = textLines(diff.original)
        val currentLines = textLines(text)
        val rows = alignDiff(originalLines.size, currentLines.size, diff.changes)
        left.show(diff.originalTitle, originalLines, rows.map { row -> row.original?.let { it to row.kind } })
        right.show(diff.currentTitle, currentLines, rows.map { row -> row.current?.let { it to row.kind } })
    }

    /** One side: a title above a code area. */
    private inner class Side {
        val area = CodeArea().apply { isEditable = false }
        val pane = VirtualizedScrollPane(area).apply { styleClass += "code-view" }
        private val title = Label().apply { styleClass += "diff-title" }
        val box = BorderPane(pane).apply { top = title }

        /**
         * Shows [lines] below [title], one per row; a row is a line index and its change, or `null` for a filler row.
         */
        fun show(
            title: String,
            lines: List<String>,
            rows: List<Pair<Int, DiffKind?>?>,
        ) {
            this.title.text = title
            area.replaceText(rows.joinToString("\n") { row -> row?.let { lines[it.first] }.orEmpty() })
            if (highlighter != null && area.length > 0) area.setStyleSpans(0, styleSpans(lines, rows))
            rows.forEachIndexed { paragraph, row ->
                val kind = row?.second ?: return@forEachIndexed
                area.setParagraphStyle(paragraph, listOf(kind.styleClass))
            }
            val digits = lines.size.toString().length
            // A new factory makes the area create the line numbers again.
            area.paragraphGraphicFactory =
                IntFunction { paragraph ->
                    val row = rows.getOrNull(paragraph)
                    Label(row?.let { (it.first + 1).toString() }.orEmpty().padStart(digits)).apply {
                        styleClass += "lineno"
                        row?.second?.let { styleClass += it.styleClass }
                    }
                }
        }

        /** The highlighting of [lines] laid out on [rows]: each line's tokens, nothing on filler rows. */
        private fun styleSpans(
            lines: List<String>,
            rows: List<Pair<Int, DiffKind?>?>,
        ): StyleSpans<Collection<String>> {
            val text = lines.joinToString("\n")
            val lineStarts = IntArray(lines.size)
            for (i in 1 until lines.size) lineStarts[i] = lineStarts[i - 1] + lines[i - 1].length + 1
            val highlighted = codeStyleSpans(text, checkNotNull(highlighter))
            val builder = StyleSpansBuilder<Collection<String>>()
            rows.forEachIndexed { index, row ->
                if (row != null && lines[row.first].isNotEmpty()) {
                    val start = lineStarts[row.first]
                    highlighted
                        .subView(start, start + lines[row.first].length)
                        .forEach { builder.add(it.style, it.length) }
                }
                if (index < rows.lastIndex) builder.add(emptyList(), 1)
            }
            return builder.create()
        }
    }
}
