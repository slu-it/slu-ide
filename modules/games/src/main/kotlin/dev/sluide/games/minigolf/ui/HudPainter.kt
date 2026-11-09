package dev.sluide.games.minigolf.ui

import dev.sluide.games.common.ui.FieldPlacement
import dev.sluide.games.minigolf.Rules
import dev.sluide.games.minigolf.ScoreText
import javafx.geometry.VPos
import javafx.scene.canvas.GraphicsContext
import javafx.scene.text.TextAlignment

/** The strip at the top of the field (y 0..16): "Hole 7 · Par 3 · Stroke 2 · Total +1". */
internal object HudPainter {
    /** The HUD text; the total is left out while [toPar] is null (the first hole). */
    fun text(
        hole: Int,
        par: Int,
        stroke: Int,
        toPar: Int?,
    ): String = "Hole $hole · Par $par · Stroke $stroke" + (toPar?.let { " · Total ${ScoreText.toPar(it)}" } ?: "")

    fun paint(
        gc: GraphicsContext,
        placement: FieldPlacement,
        hole: Int,
        par: Int,
        stroke: Int,
        toPar: Int?,
    ) {
        gc.inField(placement) {
            fill = MiniGolfPalette.HUD_STRIP
            fillRect(0.0, 0.0, Rules.FIELD_WIDTH, Rules.HUD_HEIGHT)
            font = MiniGolfPalette.font(8.5)
            textAlign = TextAlignment.CENTER
            textBaseline = VPos.CENTER
            fill = MiniGolfPalette.HUD_TEXT
            fillText(text(hole, par, stroke, toPar), Rules.FIELD_WIDTH / 2, Rules.HUD_HEIGHT / 2)
        }
    }
}
