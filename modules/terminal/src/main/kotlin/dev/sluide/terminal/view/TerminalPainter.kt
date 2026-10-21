package dev.sluide.terminal.view

import com.jediterm.core.compatibility.Point
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.StyledTextConsumer
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.model.CharBuffer
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.util.CharUtils
import javafx.scene.canvas.Canvas

/**
 * What [TerminalPainter] reads from its view each time it paints; every function reads the view's current value, so
 * the painter sees changes made between two paints.
 */
internal class PaintState(
    val fonts: () -> Fonts,
    val size: () -> TermSize,
    val selection: () -> TerminalSelection?,
    val cursorX: () -> Int,
    val cursorY: () -> Int,
    val cursorVisible: () -> Boolean,
    val exited: () -> Boolean,
    val focused: () -> Boolean,
    val scrollOrigin: () -> Int,
    val setScrollOrigin: (Int) -> Unit,
)

/** Paints the screen and scrollback of a terminal's buffer, the selection and the cursor on [canvas]; on the FX thread. */
internal class TerminalPainter(
    private val canvas: Canvas,
    private val colors: TerminalColors,
    private val buffer: TerminalTextBuffer,
    private val state: PaintState,
) {
    private val cell get() = state.fonts().cell
    private val fonts get() = state.fonts()
    private val size get() = state.size()
    private var scrollOrigin
        get() = state.scrollOrigin()
        set(value) = state.setScrollOrigin(value)

    fun paint() {
        val gc = canvas.graphicsContext2D
        gc.fill = colors.defaultBackground
        gc.fillRect(0.0, 0.0, canvas.width, canvas.height)
        val range = state.selection()?.let { normalized(it.start, it.end) }
        buffer.lock()
        try {
            scrollOrigin = scrollOrigin.coerceIn(-buffer.historyLinesCount, 0)
            buffer.processHistoryAndScreenLines(
                scrollOrigin,
                size.rows,
                object : StyledTextConsumer {
                    override fun consume(
                        x: Int,
                        y: Int,
                        style: TextStyle,
                        characters: CharBuffer,
                        startRow: Int,
                    ) = drawRun(x, y - startRow, style, characters, range)

                    override fun consumeNul(
                        x: Int,
                        y: Int,
                        nulIndex: Int,
                        style: TextStyle,
                        characters: CharBuffer,
                        startRow: Int,
                    ) = drawRun(x, y - startRow, style, characters, range)

                    override fun consumeQueue(
                        x: Int,
                        y: Int,
                        nulIndex: Int,
                        startRow: Int,
                    ) {}
                },
            )
        } finally {
            buffer.unlock()
        }
        drawCursor()
    }

    private fun drawRun(
        x: Int,
        row: Int,
        style: TextStyle,
        characters: CharBuffer,
        selected: Pair<Point, Point>?,
    ) {
        val gc = canvas.graphicsContext2D
        val styleColors = colors.of(style)
        val top = row * cell.height
        gc.font = fonts.of(style.hasOption(TextStyle.Option.BOLD), style.hasOption(TextStyle.Option.ITALIC))
        for (i in 0 until characters.length) {
            val column = x + i
            val left = column * cell.width
            val isSelected = selected != null && Point(column, row + scrollOrigin).isIn(selected)
            val foreground = if (isSelected) styleColors.background else styleColors.foreground
            gc.fill = if (isSelected) styleColors.foreground else styleColors.background
            gc.fillRect(left, top, cell.width + 0.5, cell.height + 0.5)
            val char = characters[i]
            if (char == CharUtils.DWC || char == CharUtils.NUL_CHAR || char == ' ') continue
            gc.fill = foreground
            gc.fillText(char.toString(), left, top + cell.baseline)
            if (style.hasOption(TextStyle.Option.UNDERLINED)) {
                gc.fillRect(left, top + cell.baseline + 1, cell.width, 1.0)
            }
        }
    }

    private fun drawCursor() {
        if (!state.cursorVisible() || state.exited()) return
        val row = state.cursorY() - 1 - scrollOrigin
        if (row !in 0 until size.rows) return
        val gc = canvas.graphicsContext2D
        gc.fill = colors.defaultForeground.deriveColor(0.0, 1.0, 1.0, if (state.focused()) 0.7 else 0.0)
        gc.stroke = colors.defaultForeground
        val left = state.cursorX() * cell.width
        val top = row * cell.height
        if (state.focused()) {
            gc.fillRect(
                left,
                top,
                cell.width,
                cell.height,
            )
        } else {
            gc.strokeRect(left, top, cell.width, cell.height)
        }
    }

    private fun normalized(
        a: Point,
        b: Point,
    ): Pair<Point, Point> = if (a.y < b.y || (a.y == b.y && a.x <= b.x)) a to b else b to a

    /** Whether this cell lies in the selection from the first point up to, but excluding, the second, like the copied text. */
    private fun Point.isIn(range: Pair<Point, Point>): Boolean {
        val (start, end) = range
        val afterStart = y > start.y || (y == start.y && x >= start.x)
        val beforeEnd = y < end.y || (y == end.y && x < end.x)
        return afterStart && beforeEnd
    }
}
