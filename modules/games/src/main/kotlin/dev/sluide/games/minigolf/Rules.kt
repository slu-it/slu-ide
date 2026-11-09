package dev.sluide.games.minigolf

/** The numbers of Mini Golf (ADR 0152). Lengths in field units, speeds in u/s, accelerations in u/s². */
internal object Rules {
    const val FIELD_WIDTH = 320.0
    const val FIELD_HEIGHT = 240.0

    /** The HUD strip at the top; holes lie below it. */
    const val HUD_HEIGHT = 16.0

    /** Rails stay this far from the field edges, so they are visible. */
    const val HOLE_MARGIN = 6.0

    const val STEPS_PER_SECOND = 60
    const val STEP_SECONDS = 1.0 / STEPS_PER_SECOND

    const val BALL_RADIUS = 2.5
    const val CUP_RADIUS = 4.5
    const val TUNNEL_RADIUS = 5.0

    /** The speed of a full-power shot, and the cap after a bumper. */
    const val MAX_SPEED = 300.0

    const val FELT_DECELERATION = 60.0
    const val SAND_DECELERATION = 300.0

    /** Rolling friction on a slope, lower than on flat felt so that a slope of about 50 u/s² always drains. */
    const val SLOPE_DECELERATION = 20.0

    /** A ball slower than this comes to rest, unless it is on a slope. */
    const val REST_SPEED = 2.0

    const val WALL_RESTITUTION = 0.75
    const val BUMPER_RESTITUTION = 1.15

    /** A ball whose centre enters the cup at most this fast drops. */
    const val CAPTURE_SPEED = 90.0

    /** A faster ball lips out: turned by at most this angle (radians) toward the centre and slowed by this factor. */
    const val LIP_OUT_TURN = 0.3
    const val LIP_OUT_SPEED_FACTOR = 0.7

    /** The longest move of one sub-step, so that no ball passes through a wall. */
    const val MAX_SUB_STEP = 1.0

    /** A hole not finished when the stroke count reaches this is picked up and scores [PICKED_UP_SCORE]. */
    const val STROKE_CAP = 7
    const val PICKED_UP_SCORE = 8

    /** A roll that lasts this long (20 s) stops where it is. */
    const val ROLL_LIMIT_STEPS = 1200

    /** How long the ball drops before the scorecard (1 s), and after a hole in one, for the burst (1.5 s). */
    const val HOLED_STEPS = 60
    const val HOLE_IN_ONE_STEPS = 90

    /** The ghost path keeps the ball's position every this many steps. */
    const val GHOST_SAMPLE_STEPS = 3
}
