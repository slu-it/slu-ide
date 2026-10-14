package dev.sluide.fileviewers

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.text.Font
import javafx.scene.text.FontPosture
import org.fxmisc.richtext.CodeArea
import org.reactfx.collection.LiveList
import java.util.function.IntFunction

/**
 * Columns beside the line numbers and change markers of a code view, e.g. icons to run what a line holds. Code views
 * implement it.
 */
interface GutterColumns {
    /**
     * Adds a column right of the line numbers, change markers and the columns added before: [column] gives the node
     * to show in the row of a paragraph (0-based), or `null` for none. It is asked whenever a row is created: for the
     * visible paragraphs, again when a paragraph moves (its index changes) and on [refreshGutter].
     */
    fun addGutterColumn(column: (paragraph: Int) -> Node?)

    /** Creates the visible rows of the gutter again, e.g. after what a column shows changed. */
    fun refreshGutter()
}

/**
 * The column left of the text of [area], one row per paragraph: the line number (style class `lineno`, like
 * RichTextFX's `LineNumberFactory`), a thin [change marker][markers] (style class [MARKER_STYLE] plus the
 * [LineMarker.styleClass]) and the nodes of further [columns][addColumn], e.g. icons to run something.
 *
 * The rows are created by the area's paragraph graphic factory, again for a paragraph whenever its index changes. The
 * factory is replaced, so that the area creates all rows again, only when the markers, the number of digits of the
 * line numbers or a column ([refresh]) change.
 */
internal class Gutter(
    private val area: CodeArea,
) {
    private val columns = mutableListOf<(Int) -> Node?>()
    private var digits = 0

    /** The change marker of each line; lines beyond the list have none. */
    var markers: List<LineMarker?> = emptyList()
        set(value) {
            if (value == field) return
            field = value
            refresh()
        }

    init {
        LiveList.sizeOf(area.paragraphs).observeChanges { _, _, count ->
            if (digitsOf(count) != digits) refresh()
        }
        refresh()
    }

    /** Adds a column right of the marker; [column] gives the node of a paragraph's row, or `null` for none. */
    fun addColumn(column: (paragraph: Int) -> Node?) {
        columns += column
        refresh()
    }

    /** Creates all rows again, e.g. after what a column shows changed. */
    fun refresh() {
        digits = digitsOf(area.paragraphs.size)
        val digits = digits
        val markers = markers
        val columns = columns.toList()
        area.paragraphGraphicFactory =
            IntFunction { paragraph ->
                val marker =
                    Region().apply {
                        styleClass += MARKER_STYLE
                        markers.getOrNull(paragraph)?.let { styleClass += it.styleClass }
                        setMinSize(MARKER_WIDTH, 0.0)
                        setPrefSize(MARKER_WIDTH, 0.0)
                        setMaxSize(MARKER_WIDTH, Double.MAX_VALUE)
                    }
                HBox(
                    lineNumber(paragraph, digits),
                    marker,
                    *columns.mapNotNull { it(paragraph) }.toTypedArray(),
                ).apply {
                    styleClass += GUTTER_STYLE
                }
            }
    }

    /** The number of [paragraph] right-aligned to [digits] digits, looking like RichTextFX's line numbers. */
    private fun lineNumber(
        paragraph: Int,
        digits: Int,
    ) = Label((paragraph + 1).toString().padStart(digits)).apply {
        font = LINE_NUMBER_FONT
        padding = LINE_NUMBER_PADDING
        alignment = Pos.TOP_RIGHT
        styleClass += "lineno"
    }

    private fun digitsOf(count: Int) = maxOf(1, count).toString().length

    companion object {
        /** The style class of a row of the gutter. */
        const val GUTTER_STYLE = "gutter"

        /** The style class of a change marker. */
        const val MARKER_STYLE = "change-marker"

        private const val MARKER_WIDTH = 4.0

        // As in LineNumberFactory; the editor font and syntax.css override most of it.
        private val LINE_NUMBER_FONT = Font.font("monospace", FontPosture.ITALIC, 13.0)
        private val LINE_NUMBER_PADDING = Insets(0.0, 5.0, 0.0, 5.0)
    }
}
