package dev.sluide.fileviewers

import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.model.StyleSpans
import org.fxmisc.richtext.model.StyleSpansBuilder
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignA
import org.reactfx.value.Var
import java.io.IOException
import java.nio.file.Path
import java.util.function.IntFunction
import kotlin.io.path.readText
import kotlin.math.abs

/** A file shown side by side with an original version of it, the differences coloured (ADR 0015). */
interface DiffFileView : FileView {
    /** Reads the file again and shows it against [diff], keeping the scroll position. */
    @Throws(IOException::class)
    fun show(diff: TextDiff)
}

/**
 * Read-only side-by-side view: the original on the left, the file on the right, with filler rows so that both sides
 * have the same rows and scroll together. Removed, modified and added lines get the [DiffKind] style classes on their
 * paragraph and line number, and the changed words within modified lines [CHANGED_WORDS_STYLE] (ADR 0019). Text is
 * highlighted with [highlighter] if there is one.
 *
 * A toolbar above both sides jumps to the first, previous, next and last change, seen from the caret (F7 and
 * Shift+F7 for next and previous), and tells which change the caret is in.
 */
internal class SideBySideDiffView(
    private val file: Path,
    private val highlighter: SyntaxHighlighter?,
    private var diff: TextDiff,
) : DiffFileView {
    private val left = Side()
    private val right = Side()

    private var navigation = DiffNavigation(emptyList())

    /** The row of the caret, on either side; changes are found from here. */
    private var row = 0

    /** Set while the text is replaced, which moves the carets. */
    private var rendering = false

    private val counter = Label().apply { styleClass += "diff-counter" }
    private val firstButton = navigationButton(MaterialDesignA.ARROW_COLLAPSE_UP, "First change") { navigation.first() }
    private val previousButton =
        navigationButton(MaterialDesignA.ARROW_UP, "Previous change (Shift+F7)") { navigation.previous(row) }
    private val nextButton = navigationButton(MaterialDesignA.ARROW_DOWN, "Next change (F7)") { navigation.next(row) }
    private val lastButton = navigationButton(MaterialDesignA.ARROW_COLLAPSE_DOWN, "Last change") { navigation.last() }

    private val root =
        BorderPane(SplitPane(left.box, right.box)).apply {
            styleClass += "diff-view"
            for (sheet in listOf("syntax.css", "preview-view.css")) {
                stylesheets += checkNotNull(SideBySideDiffView::class.java.getResource(sheet)).toExternalForm()
            }
            top =
                HBox(
                    counter,
                    Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                    firstButton,
                    previousButton,
                    nextButton,
                    lastButton,
                ).apply {
                    styleClass += "diff-toolbar"
                    alignment = Pos.CENTER_LEFT
                }
            addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                if (event.code != KeyCode.F7) return@addEventFilter
                (if (event.isShiftDown) navigation.previous(row) else navigation.next(row))?.let(::jumpTo)
                event.consume()
            }
        }

    init {
        // Both sides have the same rows, so the same scroll position shows the same rows.
        keepEqual(left.area.estimatedScrollYProperty(), right.area.estimatedScrollYProperty())
        keepEqual(left.area.estimatedScrollXProperty(), right.area.estimatedScrollXProperty())
        for (side in listOf(left, right)) {
            side.area.currentParagraphProperty().subscribe { paragraph ->
                if (!rendering) {
                    row = paragraph
                    updateToolbar()
                }
            }
        }
        render(file.readText())
        moveCarets(0)
    }

    override val node: Node get() = root

    override fun reload() = show(diff)

    override fun show(diff: TextDiff) {
        val text = file.readText()
        val scrollX = right.pane.estimatedScrollX
        val scrollY = right.pane.estimatedScrollY
        this.diff = diff
        render(text)
        moveCarets(row.coerceAtMost(right.area.paragraphs.size - 1))
        // Replacing the text moves the view; lay out now, then scroll back.
        root.layout()
        right.pane.scrollXToPixel(scrollX)
        right.pane.scrollYToPixel(scrollY)
    }

    private fun render(text: String) {
        val originalLines = textLines(diff.original)
        val currentLines = textLines(text)
        val rows = alignDiff(originalLines.size, currentLines.size, diff.changes)
        val words = wordChanges(originalLines, currentLines, diff.changes)
        val leftRows = rows.map { row -> row.original?.let { it to row.kind } }
        val rightRows = rows.map { row -> row.current?.let { it to row.kind } }
        rendering = true
        try {
            left.show(diff.originalTitle, originalLines, leftRows, words.original)
            right.show(diff.currentTitle, currentLines, rightRows, words.current)
        } finally {
            rendering = false
        }
        navigation = DiffNavigation(changeBlocks(rows))
        updateToolbar()
    }

    /** Puts both carets at the start of [row]. */
    private fun moveCarets(row: Int) {
        this.row = row
        left.area.moveTo(row, 0)
        right.area.moveTo(row, 0)
        updateToolbar()
    }

    /** Moves the carets to the change [rows] and shows it a few rows below the top. */
    private fun jumpTo(rows: IntRange) {
        moveCarets(rows.first)
        right.area.showParagraphAtTop(maxOf(0, rows.first - CONTEXT_ROWS))
        right.pane.scrollXToPixel(0.0)
    }

    private fun updateToolbar() {
        val changes = navigation.blocks.size
        val index = navigation.indexAt(row)
        counter.text =
            when {
                changes == 0 -> "No changes"
                index != null -> "Change ${index + 1} of $changes"
                changes == 1 -> "1 change"
                else -> "$changes changes"
            }
        firstButton.isDisable = changes == 0
        lastButton.isDisable = changes == 0
        previousButton.isDisable = navigation.previous(row) == null
        nextButton.isDisable = navigation.next(row) == null
    }

    /** A toolbar button with [icon] that jumps to the change [target] gives. */
    private fun navigationButton(
        icon: Ikon,
        tooltip: String,
        target: () -> IntRange?,
    ) = Button(null, FontIcon(icon)).apply {
        styleClass += "diff-nav"
        this.tooltip = Tooltip(tooltip)
        setOnAction { target()?.let(::jumpTo) }
    }

    /**
     * Keeps the scroll positions [a] and [b] equal. Not a bidirectional binding: a side's position passes through
     * intermediate values while it lays out, and copying those back and forth leaves the sides apart. Instead, the
     * position of the side that moved last is copied once things have settled.
     */
    private fun keepEqual(
        a: Var<Double>,
        b: Var<Double>,
    ) {
        var moved: Pair<Var<Double>, Var<Double>>? = null
        for ((from, to) in listOf(a to b, b to a)) {
            from.addListener(
                ChangeListener { _, _, _ ->
                    if (moved == null) {
                        Platform.runLater {
                            val (source, target) = checkNotNull(moved)
                            moved = null
                            if (abs(target.value - source.value) > 0.5) target.value = source.value
                        }
                    }
                    moved = from to to
                },
            )
        }
    }

    private companion object {
        /** Unchanged rows shown above a change jumped to. */
        const val CONTEXT_ROWS = 3
    }

    /** One side: a title above a code area. */
    private inner class Side {
        val area = CodeArea().apply { isEditable = false }
        val pane = VirtualizedScrollPane(area).apply { styleClass += "code-view" }
        private val title = Label().apply { styleClass += "diff-title" }
        val box = BorderPane(pane).apply { top = title }

        /**
         * Shows [lines] below [title], one per row; a row is a line index and its change, or `null` for a filler row.
         * [changedWords] are the column ranges of the changed words by line index.
         */
        fun show(
            title: String,
            lines: List<String>,
            rows: List<Pair<Int, DiffKind?>?>,
            changedWords: Map<Int, List<IntRange>>,
        ) {
            this.title.text = title
            area.replaceText(rows.joinToString("\n") { row -> row?.let { lines[it.first] }.orEmpty() })
            if ((highlighter != null || changedWords.isNotEmpty()) && area.length > 0) {
                area.setStyleSpans(0, styleSpans(lines, rows, changedWords))
            }
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

        /**
         * The styles of [lines] laid out on [rows]: each line's tokens if there is a [highlighter], plus the
         * [changedWords]; nothing on filler rows.
         */
        private fun styleSpans(
            lines: List<String>,
            rows: List<Pair<Int, DiffKind?>?>,
            changedWords: Map<Int, List<IntRange>>,
        ): StyleSpans<Collection<String>> {
            val lineStarts = IntArray(lines.size)
            for (i in 1 until lines.size) lineStarts[i] = lineStarts[i - 1] + lines[i - 1].length + 1
            val highlighted = highlighter?.let { codeStyleSpans(lines.joinToString("\n"), it) }
            val builder = StyleSpansBuilder<Collection<String>>()
            rows.forEachIndexed { index, row ->
                val line = row?.first
                if (line != null && lines[line].isNotEmpty()) {
                    val length = lines[line].length
                    val tokens =
                        highlighted?.subView(lineStarts[line], lineStarts[line] + length)
                            ?: StyleSpans.singleton<Collection<String>>(emptyList(), length)
                    val styles = changedWords[line]?.let { tokens.overlay(wordSpans(it, length)) { a, b -> a + b } }
                    (styles ?: tokens).forEach { builder.add(it.style, it.length) }
                }
                if (index < rows.lastIndex) builder.add(emptyList(), 1)
            }
            return builder.create()
        }

        /** [CHANGED_WORDS_STYLE] on the column ranges [words] of a line of [length] characters. */
        private fun wordSpans(
            words: List<IntRange>,
            length: Int,
        ): StyleSpans<Collection<String>> {
            val builder = StyleSpansBuilder<Collection<String>>()
            var column = 0
            for (word in words) {
                if (word.first > column) builder.add(emptyList(), word.first - column)
                builder.add(listOf(CHANGED_WORDS_STYLE), word.last + 1 - word.first)
                column = word.last + 1
            }
            if (length > column) builder.add(emptyList(), length - column)
            return builder.create()
        }
    }
}
