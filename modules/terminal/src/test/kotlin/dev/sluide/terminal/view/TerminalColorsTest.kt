package dev.sluide.terminal.view

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import javafx.scene.paint.Color
import java.util.EnumSet
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalColorsTest {
    private val colors = TerminalColors(defaultForeground = Color.WHITE, defaultBackground = Color.BLACK)

    @Test
    fun `cells without colors use the defaults`() {
        assertEquals(CellColors(Color.WHITE, Color.BLACK), colors.of(TextStyle.EMPTY))
    }

    @Test
    fun `ansi, 256 and true colors are resolved`() {
        val style = TextStyle(TerminalColor.index(1), TerminalColor.index(196))
        assertEquals(CellColors(Color.rgb(205, 0, 0), Color.rgb(255, 0, 0)), colors.of(style))

        val trueColor = TextStyle(TerminalColor.rgb(1, 2, 3), null)
        assertEquals(Color.rgb(1, 2, 3), colors.of(trueColor).foreground)
    }

    @Test
    fun `inverse swaps foreground and background, hidden hides the text`() {
        val inverse = TextStyle(null, null, EnumSet.of(TextStyle.Option.INVERSE))
        assertEquals(CellColors(Color.BLACK, Color.WHITE), colors.of(inverse))

        val hidden = TextStyle(null, null, EnumSet.of(TextStyle.Option.HIDDEN))
        assertEquals(CellColors(Color.BLACK, Color.BLACK), colors.of(hidden))
    }

    @Test
    fun `the grid fits whole cells and keeps a minimum size`() {
        val cell = CellSize(width = 8.0, height = 16.0, baseline = 12.0)

        assertEquals(TermSize(100, 25), cell.gridSize(807.0, 415.0))
        assertEquals(TermSize(5, 2), cell.gridSize(0.0, 0.0))
    }
}
