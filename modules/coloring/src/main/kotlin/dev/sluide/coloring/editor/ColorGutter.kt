package dev.sluide.coloring.editor

import dev.sluide.coloring.detect.FoundColor
import dev.sluide.coloring.ui.Swatch
import dev.sluide.coloring.ui.toFxColor
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.scene.shape.Rectangle

/** The swatches of the gutter of code editors (ADR 0109): their node and the decisions around them. */
internal object ColorGutter {
    const val MAX_TOOLTIP_COLORS = 10
    const val MAX_ITEM_TEXT = 30

    /** The width of the gutter column: the swatch with room on both sides. */
    const val COLUMN_WIDTH = 18.0
    private const val INNER = 8.0
    private const val OUTER = 10.0
    private val STYLESHEET = ColorGutter::class.java.getResource("color-gutter.css")?.toExternalForm()

    /** The color texts of [found], one per line, up to [MAX_TOOLTIP_COLORS], then how many more there are. */
    fun tooltipText(found: List<FoundColor>): String {
        val lines = found.take(MAX_TOOLTIP_COLORS).map { it.text }
        val more = found.size - lines.size
        return (if (more > 0) lines + "…and $more more" else lines).joinToString("\n")
    }

    /** [text] shortened to [MAX_ITEM_TEXT] characters, with an ellipsis. */
    fun shortened(text: String): String = if (text.length <= MAX_ITEM_TEXT) text else text.take(MAX_ITEM_TEXT - 1) + "…"

    /** The swatch of the first of [found]; a primary click calls [onClick] with it, the caret stays where it is. */
    fun swatch(
        found: List<FoundColor>,
        onClick: (FoundColor) -> Unit,
    ): Node {
        val first = found.first()
        var installed = false
        return StackPane(
            Rectangle(INNER, INNER).apply { fill = Swatch.CHECKER },
            Rectangle(INNER, INNER).apply { fill = first.color.toFxColor() },
        ).apply {
            styleClass += "color-gutter-swatch"
            STYLESHEET?.let { stylesheets += it }
            setMinSize(OUTER, OUTER)
            setPrefSize(OUTER, OUTER)
            setMaxSize(OUTER, OUTER)
            cursor = Cursor.HAND
            // Installed on the first hover: most rows are never hovered.
            addEventHandler(MouseEvent.MOUSE_ENTERED) {
                if (!installed) {
                    installed = true
                    Tooltip.install(this, Tooltip(tooltipText(found)))
                }
            }
            // Not pressed into the text: the caret stays where it is.
            addEventHandler(MouseEvent.MOUSE_PRESSED) { it.consume() }
            addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
                if (event.button == MouseButton.PRIMARY) onClick(first)
                event.consume()
            }
        }
    }
}
