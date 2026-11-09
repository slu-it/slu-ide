package dev.sluide.games.minigolf.ui

import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.FontWeight

/** The colours and fonts of Mini Golf. The field is dark in both themes, so the palette does not follow the theme. */
internal object MiniGolfPalette {
    val SURROUND: Color = Color.web("#14191f")
    val HUD_STRIP: Color = Color.web("#0d1116")
    val HUD_TEXT: Color = Color.web("#d6dde6")

    val FELT_LIGHT: Color = Color.web("#2f9a57")
    val FELT_DARK: Color = Color.web("#1f7a41")

    val RAIL_EDGE: Color = Color.web("#3a2615")
    val RAIL: Color = Color.web("#8a5a32")
    val RAIL_LIGHT: Color = Color.web("#b27a4a")

    val SAND: Color = Color.web("#e0c98a")
    val SAND_DARK: Color = Color.web("#b99a58")
    val SAND_LIGHT: Color = Color.web("#f3e6b8")

    val WATER: Color = Color.web("#1e6fb8")
    val WATER_DEEP: Color = Color.web("#154f86")
    val WATER_EDGE: Color = Color.web("#8fd0f5")

    val SLOPE_ARROW: Color = Color.web("#e6ffe9", 0.6)

    val BUMPER: Color = Color.web("#d6504a")
    val BUMPER_RING: Color = Color.web("#ffd6c9")
    val BUMPER_DARK: Color = Color.web("#7a1f1c")

    val TUNNEL_HOLE: Color = Color.web("#07090c")
    val TUNNEL_RING: Color = Color.web("#8e7bd1")
    val TUNNEL_ARROW: Color = Color.web("#d9d0ff")

    val STEEL: Color = Color.web("#aeb8c4")
    val STEEL_LIGHT: Color = Color.web("#e8eef5")
    val STEEL_DARK: Color = Color.web("#4b5560")

    val CUP: Color = Color.web("#050607")
    val CUP_RIM: Color = Color.web("#9fb7a3")
    val FLAG_POLE: Color = Color.web("#f2f2f2")
    val FLAG: Color = Color.web("#e8453c")

    val BALL: Color = Color.web("#ffffff")
    val BALL_SHADE: Color = Color.web("#c3cad2")
    val SHADOW: Color = Color.color(0.0, 0.0, 0.0, 0.35)

    val GHOST: Color = Color.color(1.0, 1.0, 1.0, 0.28)

    val CARD_DIM: Color = Color.color(0.03, 0.05, 0.07, 0.82)
    val CARD_PANEL: Color = Color.web("#1b232c")
    val CARD_LINE: Color = Color.web("#3a4654")
    val CARD_LABEL: Color = Color.web("#8fa0b3")
    val CARD_TEXT: Color = Color.web("#e4e9ef")
    val ACCENT: Color = Color.web("#ffd35c")

    val UNDER_PAR: Color = Color.web("#5fe08a")
    val AT_PAR: Color = Color.web("#e4e9ef")
    val OVER_PAR: Color = Color.web("#ff8a73")
    val NOT_PLAYED: Color = Color.web("#5d6b7a")

    /** The colour of a scorecard cell by its result. */
    fun resultColor(result: HoleResult): Color =
        when (result) {
            HoleResult.UNDER_PAR -> UNDER_PAR
            HoleResult.AT_PAR -> AT_PAR
            HoleResult.OVER_PAR -> OVER_PAR
            HoleResult.NOT_PLAYED -> NOT_PLAYED
        }

    private val fonts = HashMap<Double, Font>()

    /** The bold monospaced font of [size] field units (the gc transform scales it), cached. */
    fun font(size: Double): Font = fonts.getOrPut(size) { Font.font("Monospaced", FontWeight.BOLD, size) }
}
