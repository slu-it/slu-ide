package dev.sluide.games.blockbreaker

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The paddle as the ball sees it: left edge and width; top and height are fixed. */
internal class PaddleBox(
    val x: Double,
    val width: Double,
)

/**
 * A ball (4 x 4, x and y are left/top) with a unit direction ([dx], [dy]). [advance] moves it in sub-steps of at most
 * 2 px and resolves walls, paddle and bricks in that order.
 */
internal class Ball(
    var x: Double,
    var y: Double,
    dx: Double,
    dy: Double,
) {
    var dx = dx
        private set
    var dy = dy
        private set

    /** Bounces off walls and steel since the last paddle hit or hit of a breakable brick. */
    var bounces = 0
        private set

    init {
        aim(dx, dy)
    }

    fun aim(
        newDx: Double,
        newDy: Double,
    ) {
        val length = hypot(newDx, newDy)
        dx = newDx / length
        dy = newDy / length
    }

    /** Rotates the direction by [degrees] (positive turns clockwise on screen) and keeps the minimum angle. */
    fun rotate(degrees: Double) {
        val angle = atan2(dy, dx) + Math.toRadians(degrees)
        dx = cos(angle)
        dy = sin(angle)
        clampAngle()
    }

    fun copyRotated(degrees: Double): Ball = Ball(x, y, dx, dy).also { it.rotate(degrees) }

    /** Keeps the angle from horizontal at 15 degrees or more. */
    fun clampAngle() {
        val minDy = sin(Math.toRadians(Rules.MIN_ANGLE_DEGREES))
        if (abs(dy) >= minDy) return
        val signY = if (dy > 0) 1.0 else -1.0
        val signX = if (dx < 0) -1.0 else 1.0
        dy = signY * minDy
        dx = signX * cos(Math.toRadians(Rules.MIN_ANGLE_DEGREES))
    }

    /**
     * Moves the ball by [distance] px. Hits on bricks are appended to [hits]; the result is the number of paddle
     * bounces.
     */
    fun advance(
        distance: Double,
        paddle: PaddleBox,
        bricks: BrickGrid,
        hits: MutableList<BrickHitResult>,
    ): Int {
        val parts = max(1, ceil(distance / Rules.MAX_SUB_STEP - 1e-9).toInt())
        val length = distance / parts
        var paddleHits = 0
        repeat(parts) {
            x += dx * length
            y += dy * length
            bounceWalls()
            if (bouncePaddle(paddle)) paddleHits++
            bounceBrick(bricks, hits)
        }
        return paddleHits
    }

    private fun bounceWalls() {
        if (x < Rules.PLAY_LEFT) {
            x = Rules.PLAY_LEFT.toDouble()
            dx = abs(dx)
            countBounce()
        } else if (x + Rules.BALL_SIZE > Rules.PLAY_RIGHT) {
            x = (Rules.PLAY_RIGHT - Rules.BALL_SIZE).toDouble()
            dx = -abs(dx)
            countBounce()
        }
        if (y < Rules.TOP_WALL) {
            y = Rules.TOP_WALL.toDouble()
            dy = abs(dy)
            countBounce()
        }
    }

    private fun bouncePaddle(paddle: PaddleBox): Boolean {
        val overlaps =
            dy > 0 &&
                x < paddle.x + paddle.width &&
                paddle.x < x + Rules.BALL_SIZE &&
                y + Rules.BALL_SIZE > Rules.PADDLE_TOP &&
                y < Rules.PADDLE_TOP + Rules.PADDLE_HEIGHT
        if (!overlaps) return false
        val half = paddle.width / 2
        val offset = ((x + Rules.BALL_SIZE / 2.0 - (paddle.x + half)) / (half + 2)).coerceIn(-1.0, 1.0)
        val angle = Math.toRadians(offset * Rules.PADDLE_BOUNCE_DEGREES)
        dx = sin(angle)
        dy = -cos(angle)
        y = (Rules.PADDLE_TOP - Rules.BALL_SIZE).toDouble()
        bounces = 0
        return true
    }

    private fun bounceBrick(
        bricks: BrickGrid,
        hits: MutableList<BrickHitResult>,
    ) {
        val size = Rules.BALL_SIZE
        val firstColumn = floor((x - Rules.PLAY_LEFT) / Rules.BRICK_WIDTH).toInt()
        val lastColumn = floor((x + size - Rules.PLAY_LEFT) / Rules.BRICK_WIDTH).toInt()
        val firstRow = floor((y - Rules.BRICK_TOP) / Rules.BRICK_HEIGHT).toInt()
        val lastRow = floor((y + size - Rules.BRICK_TOP) / Rules.BRICK_HEIGHT).toInt()
        var bestColumn = -1
        var bestRow = -1
        var bestArea = 0.0
        var bestX = 0.0
        var bestY = 0.0
        for (column in firstColumn..lastColumn) {
            for (row in firstRow..lastRow) {
                if (!bricks.hasBrick(column, row)) continue
                val overlapX =
                    min(x + size, (brickLeft(column) + Rules.BRICK_WIDTH).toDouble()) -
                        max(x, brickLeft(column).toDouble())
                val overlapY =
                    min(y + size, (brickTop(row) + Rules.BRICK_HEIGHT).toDouble()) - max(y, brickTop(row).toDouble())
                if (overlapX <= 0 || overlapY <= 0 || overlapX * overlapY <= bestArea) continue
                bestColumn = column
                bestRow = row
                bestArea = overlapX * overlapY
                bestX = overlapX
                bestY = overlapY
            }
        }
        if (bestColumn < 0) return
        val left = brickLeft(bestColumn)
        val top = brickTop(bestRow)
        if (bestX < bestY) {
            if (x + size / 2.0 < left + Rules.BRICK_WIDTH / 2.0) {
                x = (left - size).toDouble()
                dx = -abs(dx)
            } else {
                x = (left + Rules.BRICK_WIDTH).toDouble()
                dx = abs(dx)
            }
        } else if (y + size / 2.0 < top + Rules.BRICK_HEIGHT / 2.0) {
            y = (top - size).toDouble()
            dy = -abs(dy)
        } else {
            y = (top + Rules.BRICK_HEIGHT).toDouble()
            dy = abs(dy)
        }
        val result = bricks.hit(bestColumn, bestRow) ?: return
        hits.add(result)
        if (result.breakable) {
            bounces = 0
            clampAngle()
        } else {
            countBounce()
        }
    }

    private fun countBounce() {
        clampAngle()
        bounces++
        if (bounces >= Rules.ANTI_LOOP_BOUNCES) {
            rotate(if (dx * dy > 0) Rules.ANTI_LOOP_DEGREES else -Rules.ANTI_LOOP_DEGREES)
            bounces = 0
        }
    }

    private fun brickLeft(column: Int) = Rules.PLAY_LEFT + column * Rules.BRICK_WIDTH

    private fun brickTop(row: Int) = Rules.BRICK_TOP + row * Rules.BRICK_HEIGHT
}
