package dev.sluide.games.blockbreaker.ui

import dev.sluide.games.blockbreaker.BlockBreakerGame
import dev.sluide.games.blockbreaker.Rules
import dev.sluide.games.common.ui.FieldPlacement
import javafx.scene.canvas.GraphicsContext
import javafx.scene.image.Image
import javafx.scene.paint.Color

/** The colours of the field, our own in both themes. */
internal object Palette {
    // Keep in sync with .bb-board in block-breaker.css.
    val BACKGROUND: Color = Color.web("#0a0d18")
    val WALL: Color = Color.web("#4a5670")
    val WALL_EDGE: Color = Color.web("#6b7898")
    val BALL: Color = Color.web("#ffffff")
    val PADDLE: Color = Color.web("#7fb2ff")
    val HUD: Color = Color.web("#c8d3f5")
    val ACCENT: Color = Color.web("#ffb347")
}

/** What a painter needs to draw one frame: the context, the field's placement and the images. */
internal class Frame(
    val gc: GraphicsContext,
    val place: FieldPlacement,
    val images: BrickImages,
) {
    fun rect(
        color: Color,
        fx: Double,
        fy: Double,
        fw: Double,
        fh: Double,
    ) {
        gc.fill = color
        gc.fillRect(place.x(fx), place.y(fy), place.spanWidth(fx, fw), place.spanHeight(fy, fh))
    }

    /**
     * Draws [image] into the snapped rect of the field rect [fw] x [fh] at ([fx], [fy]), like [rect], so that
     * neighbouring images meet exactly at every scale (their pixel size can be one device pixel short).
     */
    fun image(
        image: Image,
        fx: Double,
        fy: Double,
        fw: Double,
        fh: Double,
    ) = gc.drawImage(
        image,
        place.x(fx),
        place.y(fy),
        place.x(fx + fw) - place.x(fx),
        place.y(fy + fh) - place.y(fy),
    )
}

/** Draws the walls and everything of the game that lives on the field. */
internal class FieldPainter {
    fun paint(
        f: Frame,
        game: BlockBreakerGame,
    ) {
        walls(f)
        // Under the balls and capsules, which fall past them.
        lives(f, game.lives)
        for (brick in game.bricks) {
            val x = (Rules.PLAY_LEFT + brick.column * Rules.BRICK_WIDTH).toDouble()
            val y = (Rules.BRICK_TOP + brick.row * Rules.BRICK_HEIGHT).toDouble()
            val image = f.images.brick(brick.kind, brick.colourTier, brick.hitsLeft)
            f.image(image, x, y, Rules.BRICK_WIDTH.toDouble(), Rules.BRICK_HEIGHT.toDouble())
        }
        for (capsule in game.capsules) {
            val image = f.images.capsule(capsule.kind)
            f.image(image, capsule.x, capsule.y, Rules.CAPSULE_WIDTH.toDouble(), Rules.CAPSULE_HEIGHT.toDouble())
        }
        val width = Math.round(game.paddleWidth).toInt()
        f.image(f.images.paddle(width), game.paddleX, Rules.PADDLE_TOP.toDouble(), width.toDouble(), PADDLE_HEIGHT)
        val size = Rules.BALL_SIZE.toDouble()
        for (ball in game.balls) f.rect(Palette.BALL, ball.x, ball.y, size, size)
    }

    /** One small dim paddle per spare ball: the ball in play is one of [lives], at most [Rules.MAX_LIVES] - 1. */
    private fun lives(
        f: Frame,
        lives: Int,
    ) {
        repeat((lives - 1).coerceIn(0, Rules.MAX_LIVES - 1)) { i ->
            f.rect(Palette.WALL_EDGE, 4.0 + i * (LIFE_WIDTH + 3), LIVES_Y, LIFE_WIDTH, LIFE_HEIGHT)
        }
    }

    private fun walls(f: Frame) {
        val width = BlockBreakerGame.WIDTH.toDouble()
        val height = BlockBreakerGame.HEIGHT.toDouble()
        val top = Rules.TOP_WALL.toDouble()
        val left = Rules.PLAY_LEFT.toDouble()
        val right = Rules.PLAY_RIGHT.toDouble()
        f.rect(Palette.WALL, 0.0, top - TOP_WALL_THICKNESS, width, TOP_WALL_THICKNESS)
        f.rect(Palette.WALL, 0.0, top, left, height - top)
        f.rect(Palette.WALL, right, top, width - right, height - top)
        // The inner edges catch the light.
        f.rect(Palette.WALL_EDGE, left - 1, top, 1.0, height - top)
        f.rect(Palette.WALL_EDGE, right, top, 1.0, height - top)
        f.rect(Palette.WALL_EDGE, left, top - 1, right - left, 1.0)
    }

    private companion object {
        const val TOP_WALL_THICKNESS = 4.0
        const val LIVES_Y = 232.0
        const val LIFE_WIDTH = 12.0
        const val LIFE_HEIGHT = 3.0
        const val PADDLE_HEIGHT = Rules.PADDLE_HEIGHT.toDouble()
    }
}
