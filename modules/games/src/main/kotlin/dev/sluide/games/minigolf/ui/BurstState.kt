package dev.sluide.games.minigolf.ui

import dev.sluide.games.common.ui.FieldPlacement
import dev.sluide.games.minigolf.MiniGolfGame
import dev.sluide.games.minigolf.geometry.Vec
import javafx.scene.canvas.GraphicsContext

/**
 * The hole-in-one burst and the hole it belongs to. The burst's clock is the game's step count, which restarts with
 * every hole and round, so only the hole it began on may show it: a later hole reaching the same step count must not
 * replay it.
 */
internal class BurstState(
    private val burst: HoleInOneBurst = HoleInOneBurst(),
) {
    private var game: MiniGolfGame? = null
    private var holeIndex = -1

    /** Starts the burst at [cup] on the current hole of [game]. */
    fun start(
        game: MiniGolfGame,
        cup: Vec,
    ) {
        this.game = game
        holeIndex = game.holeIndex
        burst.start(cup, game.stepCount)
    }

    /** True while the burst of this very hole of [game] is on. */
    fun isActive(game: MiniGolfGame): Boolean =
        this.game === game && holeIndex == game.holeIndex && burst.isActive(game.stepCount)

    fun paint(
        gc: GraphicsContext,
        placement: FieldPlacement,
        game: MiniGolfGame,
    ) {
        if (isActive(game)) burst.paint(gc, placement, game.stepCount)
    }
}
