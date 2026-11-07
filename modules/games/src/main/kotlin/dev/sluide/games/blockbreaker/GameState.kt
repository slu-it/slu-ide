package dev.sluide.games.blockbreaker

/**
 * The player's controls for one step. The model nulls [pointerX] on a LEFT or RIGHT press until the next mouse move,
 * and the engine ignores the pointer while a key is held.
 */
internal data class Input(
    val left: Boolean = false,
    val right: Boolean = false,
    val launch: Boolean = false,
    val pointerX: Double? = null,
)

internal enum class Phase { SERVE, PLAYING, BALL_LOST, LEVEL_BREAK, OVER }

/** Read-only snapshots for the renderer; x and y are the left/top of the sprite in field pixels. */
internal data class BallState(
    val x: Double,
    val y: Double,
)

internal data class BrickState(
    val column: Int,
    val row: Int,
    val kind: BrickKind,
    val colourTier: Int,
    val hitsLeft: Int,
)

internal data class CapsuleState(
    val x: Double,
    val y: Double,
    val kind: PowerUp,
)

/** The numbers of the rules, in field pixels and steps. */
internal object Rules {
    const val PLAY_LEFT = 4
    const val PLAY_RIGHT = 316
    const val TOP_WALL = 16
    const val BRICK_WIDTH = 24
    const val BRICK_HEIGHT = 8
    const val BRICK_TOP = 32
    const val COLUMNS = 13
    const val MAX_ROWS = 10
    const val PADDLE_TOP = 220
    const val PADDLE_HEIGHT = 6
    const val PADDLE_KEY_SPEED = 4.0
    const val PADDLE_POINTER_SPEED = 10.0
    const val BALL_SIZE = 4
    const val CAPSULE_WIDTH = 12
    const val CAPSULE_HEIGHT = 6
    const val FIELD_BOTTOM = 240
    const val MAX_SUB_STEP = 2.0
    const val MIN_ANGLE_DEGREES = 15.0
    const val ANTI_LOOP_BOUNCES = 20
    const val ANTI_LOOP_DEGREES = 5.0
    const val MAX_BALLS = 5
    const val MAX_LIVES = 9
    const val MAX_CAPSULES = 2
    const val CAPSULE_POINTS = 50
    const val WIDE_STEPS = 1200
    const val SLOW_STEPS = 900
    const val WIDE_FACTOR = 1.5
    const val SLOW_FACTOR = 0.7
    const val BALL_LOST_STEPS = 60
    const val LEVEL_BREAK_STEPS = 90
    const val LEVEL_COUNT = 10
    const val MAX_SPEED_UP = 1.6
    const val SERVE_TILT_DEGREES = 15.0
    const val PADDLE_BOUNCE_DEGREES = 60.0
    const val MULTI_DEGREES = 30.0
}
