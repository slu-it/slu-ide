package dev.sluide.games.blockbreaker.ui

import dev.sluide.games.blockbreaker.BlockBreakerModel
import dev.sluide.games.blockbreaker.Phase
import dev.sluide.games.common.SessionState
import javafx.geometry.VPos
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.TextAlignment
import java.util.Locale

/** The text on the field: score, level and best in the HUD row, and the messages of the states. Field coordinates. */
internal class HudPainter {
    fun hud(
        f: Frame,
        model: BlockBreakerModel,
    ) {
        val game = model.game
        text(f, "Score ${number(game.score)}", 4.0, HUD_Y, HUD_SIZE, TextAlignment.LEFT)
        text(f, "Level ${game.level}", 160.0, HUD_Y, HUD_SIZE, TextAlignment.CENTER)
        text(f, "Best ${number(maxOf(model.best, game.score))}", 316.0, HUD_Y, HUD_SIZE, TextAlignment.RIGHT)
    }

    fun message(
        f: Frame,
        model: BlockBreakerModel,
    ) {
        val game = model.game
        when {
            model.state == SessionState.READY -> {
                line(f, model.readyText, 150.0)
                line(f, model.difficulty.label, 164.0, color = Palette.ACCENT)
            }

            model.state == SessionState.COUNTDOWN -> {
                line(f, model.countdown.toString(), 160.0, size = 40.0)
            }

            model.state == SessionState.OVER -> {
                line(f, "Game over", 140.0, size = 20.0)
                val best = if (model.isNewBest) " — new best!" else ""
                line(f, "Score ${number(game.score)}$best", 164.0)
                line(f, "F2 for a new game", 180.0, color = Palette.ACCENT)
            }

            game.phase == Phase.LEVEL_BREAK -> {
                line(f, "Level ${game.level + 1}", 160.0, size = 16.0)
            }
        }
    }

    private fun line(
        f: Frame,
        s: String,
        y: Double,
        size: Double = 10.0,
        color: Color = Palette.HUD,
    ) = text(f, s, 160.0, y, size, TextAlignment.CENTER, color)

    private fun text(
        f: Frame,
        s: String,
        x: Double,
        centerY: Double,
        size: Double,
        align: TextAlignment,
        color: Color = Palette.HUD,
    ) {
        val gc = f.gc
        gc.font = font(size * f.place.scale)
        gc.textAlign = align
        gc.textBaseline = VPos.CENTER
        gc.fill = color
        gc.fillText(s, f.place.x(x), f.place.y(centerY))
    }

    private val fonts = HashMap<Double, Font>()
    private val numbers = HashMap<Int, String>()

    private fun font(px: Double): Font {
        if (fonts.size > MAX_CACHED) fonts.clear()
        return fonts.getOrPut(px) { Font.font("Monospaced", FontWeight.BOLD, px) }
    }

    private fun number(n: Int): String {
        if (numbers.size > MAX_CACHED) numbers.clear()
        return numbers.getOrPut(n) { String.format(Locale.ROOT, "%,d", n) }
    }

    private companion object {
        const val MAX_CACHED = 64
        const val HUD_SIZE = 8.0
        const val HUD_Y = 6.0
    }
}
