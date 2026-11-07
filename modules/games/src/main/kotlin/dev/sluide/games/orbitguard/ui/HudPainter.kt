package dev.sluide.games.orbitguard.ui

import dev.sluide.games.common.SessionState
import dev.sluide.games.orbitguard.OrbitGuardModel
import dev.sluide.games.orbitguard.Phase
import dev.sluide.games.orbitguard.Sprites
import javafx.geometry.VPos
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.TextAlignment
import java.util.Locale

/** The text on the field: score, best, wave and lives, and the messages of the states. Field coordinates. */
internal class HudPainter {
    fun hud(
        f: Frame,
        model: OrbitGuardModel,
    ) {
        val game = model.game
        text(f, "SCORE ${number(game.score)}", 4.0, HUD_Y, HUD_SIZE, TextAlignment.LEFT)
        text(f, "BEST ${number(maxOf(model.best, game.score))}", 160.0, HUD_Y, HUD_SIZE, TextAlignment.CENTER)
        text(f, "WAVE ${game.wave}", 316.0, HUD_Y, HUD_SIZE, TextAlignment.RIGHT)
        // The ship on the field is one life; the icons are the reserve.
        repeat((game.lives - 1).coerceAtLeast(0)) { i ->
            f.sprite(Sprites.SHIP, Palette.SHIP, 4.0 + i * (Sprites.SHIP.width / 2 + 3), LIVES_Y, smaller = true)
        }
    }

    fun message(
        f: Frame,
        model: OrbitGuardModel,
    ) {
        val game = model.game
        when {
            model.state == SessionState.READY -> {
                line(f, "Press ← → or Space to start", 104.0)
                line(f, model.difficulty.label, 118.0, color = Palette.RAIDER)
            }

            model.state == SessionState.COUNTDOWN -> {
                line(f, model.countdown.toString(), 120.0, size = 40.0)
            }

            model.state == SessionState.OVER -> {
                line(f, "Game over", 92.0, size = 20.0)
                val best = if (model.isNewBest) " — new best!" else ""
                line(f, "Score ${number(game.score)}$best", 116.0)
                line(f, "F2 for a new game", 132.0, color = Palette.RAIDER)
            }

            game.phase == Phase.WAVE_BREAK -> {
                line(f, "Wave ${game.wave + 1}", 110.0, size = 16.0)
            }
        }
    }

    fun bonusPoints(
        f: Frame,
        points: Int,
        centerX: Double,
        y: Double,
    ) {
        text(f, points.toString(), centerX, y + 3, HUD_SIZE, TextAlignment.CENTER, Palette.BONUS)
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
        const val LIVES_Y = 230.0
    }
}
