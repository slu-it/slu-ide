package dev.sluide.fileviewers.code

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.text.Font
import javafx.scene.text.FontPosture
import org.fxmisc.richtext.CodeArea
import org.reactfx.collection.LiveList
import java.util.function.IntFunction

/**
 * The column left of the text of [area], one row per paragraph: the line number (style class `lineno`, like
 * RichTextFX's `LineNumberFactory`), a thin [change marker][markers] (style class [MARKER_STYLE] plus the
 * [LineMarker.styleClass]; clicking it calls [onMarkerClicked] while [markersClickable]) and the nodes of further
 * [columns][addColumn], e.g. icons to run something. The code editor's context menu is not shown over the gutter.
 *
 * The rows are created by the area's paragraph graphic factory, again for a paragraph whenever its index changes. The
 * factory is replaced, so that the area creates all rows again, only when the markers, the number of digits of the
 * line numbers or a column ([refresh]) change.
 */
internal class Gutter(
    private val area: CodeArea,
    /** Called with the paragraph whose change marker was clicked. */
    private val onMarkerClicked: (paragraph: Int) -> Unit = {},
) {
    private val columns = mutableListOf<Pair<Double, (Int) -> Node?>>()
    private var digits = 0

    /** The change marker of each line; lines beyond the list have none. */
    var markers: List<LineMarker?> = emptyList()
        set(value) {
            if (value == field) return
            field = value
            refresh()
        }

    /** Whether clicking a marker calls [onMarkerClicked], e.g. only while there is a diff to show. */
    var markersClickable: Boolean = false
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

    /**
     * Adds a column [width] wide right of the marker; [column] gives the node of a paragraph's row, or `null` for none.
     * Every row has the column's cell, so a row without a node is as wide as one with.
     */
    fun addColumn(
        width: Double,
        column: (paragraph: Int) -> Node?,
    ) {
        columns += width to column
        refresh()
    }

    /** Creates all rows again, e.g. after what a column shows changed. */
    fun refresh() {
        digits = digitsOf(area.paragraphs.size)
        val digits = digits
        val markers = markers
        val clickable = markersClickable
        val columns = columns.toList()
        area.paragraphGraphicFactory =
            IntFunction { paragraph ->
                val marker =
                    Region().apply {
                        styleClass += MARKER_STYLE
                        setMinSize(MARKER_WIDTH, 0.0)
                        setPrefSize(MARKER_WIDTH, 0.0)
                        setMaxSize(MARKER_WIDTH, Double.MAX_VALUE)
                        val kind = markers.getOrNull(paragraph)
                        kind?.let { styleClass += it.styleClass }
                        if (kind != null && clickable) {
                            cursor = Cursor.HAND
                            // Not pressed into the text: the caret stays where it is.
                            addEventHandler(MouseEvent.MOUSE_PRESSED) { it.consume() }
                            addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
                                if (event.button == MouseButton.PRIMARY) onMarkerClicked(paragraph)
                                event.consume()
                            }
                        }
                    }
                HBox(
                    lineNumber(paragraph, digits),
                    marker,
                    *columns.map { (width, column) -> cell(width, column(paragraph)) }.toTypedArray(),
                ).apply {
                    styleClass += GUTTER_STYLE
                    // The editor's context menu is for the text; nodes of columns may still show menus of their own.
                    addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED) { it.consume() }
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

    /** The cell of a column in a row: [width] wide and as high as the row, [node] centered in it if there is one. */
    private fun cell(
        width: Double,
        node: Node?,
    ) = StackPane().apply {
        setMinWidth(width)
        setPrefWidth(width)
        setMaxWidth(width)
        maxHeight = Double.MAX_VALUE
        // Not pressed into the text: the caret stays where it is, beside a node of a column too.
        addEventHandler(MouseEvent.MOUSE_PRESSED) { it.consume() }
        if (node != null) children += node
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
