package dev.sluide.games.minigolf

import dev.sluide.games.minigolf.geometry.Circle
import dev.sluide.games.minigolf.geometry.Polygon
import dev.sluide.games.minigolf.geometry.Segment
import dev.sluide.games.minigolf.geometry.Vec

/**
 * One hole of the course, drawn by hand with [hole]. Painters draw [greens] (the felt, outlined by rails), [blocks]
 * (solid obstacles) and [looseWalls] (single walls such as a diagonal bank); the physics only uses [walls], which holds
 * every edge of [greens] and [blocks] plus [looseWalls]. Greens must neither overlap nor touch each other, since each outline is a rail: an L-shaped lane is one
 * polygon, not two rectangles.
 */
internal data class Hole(
    val number: Int,
    val par: Int,
    val tee: Vec,
    val cup: Vec,
    val greens: List<Polygon>,
    val walls: List<Segment>,
    val looseWalls: List<Segment>,
    val blocks: List<Polygon>,
    val sand: List<Polygon>,
    val water: List<Polygon>,
    val slopes: List<Slope>,
    val bumpers: List<Circle>,
    val tunnels: List<Tunnel>,
    val movers: List<Mover>,
)

/**
 * An area that pulls the ball with a constant [acceleration] in u/s² (about 50 in magnitude). It must be stronger
 * than [Rules.SLOPE_DECELERATION] and must not overlap sand, so that a ball never rests on it.
 */
internal data class Slope(
    val area: Polygon,
    val acceleration: Vec,
)

/**
 * A pair of tunnel mouths (radius [Rules.TUNNEL_RADIUS]). A ball whose centre enters [a] leaves [b] in the direction
 * [exitB] (a unit vector) at the same speed, and the other way round; it cannot enter the mouth it came out of until it
 * has left it.
 */
internal data class Tunnel(
    val a: Vec,
    val exitA: Vec,
    val b: Vec,
    val exitB: Vec,
)

/** A point written as a pair, `40 to 120`, in the builder. */
internal typealias Point = Pair<Number, Number>

internal fun Point.toVec() = Vec(first.toDouble(), second.toDouble())

/** A polygon through [points], for [HoleBuilder.slope] or any area method of the builder. */
internal fun poly(vararg points: Point) = Polygon(points.map { it.toVec() })

/** The axis-aligned rectangle with the corners ([x1], [y1]) and ([x2], [y2]). */
internal fun rect(
    x1: Number,
    y1: Number,
    x2: Number,
    y2: Number,
) = poly(x1 to y1, x2 to y1, x2 to y2, x1 to y2)

/**
 * Builds a [Hole]:
 * ```
 * hole(1, par = 2) {
 *     green(30 to 104, 290 to 104, 290 to 152, 30 to 152)
 *     tee(50, 128)
 *     cup(268, 128)
 * }
 * ```
 * A hole needs a tee, a cup and at least one green. Field coordinates: 320 × 240, holes lie in y 16..240 and keep 6
 * units of margin to the edges.
 */
internal fun hole(
    number: Int,
    par: Int,
    build: HoleBuilder.() -> Unit,
): Hole = HoleBuilder(number, par).apply(build).toHole()

internal class HoleBuilder(
    private val number: Int,
    private val par: Int,
) {
    private var tee: Vec? = null
    private var cup: Vec? = null
    private val greens = mutableListOf<Polygon>()
    private val looseWalls = mutableListOf<Segment>()
    private val blocks = mutableListOf<Polygon>()
    private val sand = mutableListOf<Polygon>()
    private val water = mutableListOf<Polygon>()
    private val slopes = mutableListOf<Slope>()
    private val bumpers = mutableListOf<Circle>()
    private val tunnels = mutableListOf<Tunnel>()
    private val movers = mutableListOf<Mover>()

    fun tee(
        x: Number,
        y: Number,
    ) {
        tee = Vec(x.toDouble(), y.toDouble())
    }

    fun cup(
        x: Number,
        y: Number,
    ) {
        cup = Vec(x.toDouble(), y.toDouble())
    }

    /** A piece of felt; its outline is a rail. */
    fun green(vararg points: Point) = green(poly(*points))

    fun green(area: Polygon) {
        greens += area
    }

    /** A solid obstacle inside a green; its outline is a wall. */
    fun block(vararg points: Point) = block(poly(*points))

    fun block(area: Polygon) {
        blocks += area
    }

    /** A loose wall from [a] to [b], for example a diagonal bank inside a green. */
    fun wall(
        a: Point,
        b: Point,
    ) {
        looseWalls += Segment(a.toVec(), b.toVec())
    }

    fun sand(vararg points: Point) = sand(poly(*points))

    fun sand(area: Polygon) {
        sand += area
    }

    fun water(vararg points: Point) = water(poly(*points))

    fun water(area: Polygon) {
        water += area
    }

    /**
     * A slope over [area] that pulls the ball by [acceleration] (u/s², for example `0 to 50`, downwards); the pull must
     * be stronger than [Rules.SLOPE_DECELERATION], or a ball could stop on it.
     */
    fun slope(
        area: Polygon,
        acceleration: Point,
    ) {
        val pull = acceleration.toVec()
        require(pull.length > Rules.SLOPE_DECELERATION) {
            "hole $number: a slope pull of ${pull.length} does not beat the slope friction ${Rules.SLOPE_DECELERATION}"
        }
        slopes += Slope(area, pull)
    }

    fun bumper(
        x: Number,
        y: Number,
        radius: Number,
    ) {
        bumpers += Circle(Vec(x.toDouble(), y.toDouble()), radius.toDouble())
    }

    /** Mouths [a] and [b]; the exit directions need not have length 1 (`1 to -1` is fine). */
    fun tunnel(
        a: Point,
        exitA: Point,
        b: Point,
        exitB: Point,
    ) {
        tunnels += Tunnel(a.toVec(), exitA.toVec().normalized(), b.toVec(), exitB.toVec().normalized())
    }

    /** See [RotatingBar]. */
    fun rotatingBar(
        pivot: Point,
        length: Number,
        periodSteps: Int,
        phase: Double = 0.0,
        thickness: Double = 4.0,
    ) {
        movers += RotatingBar(pivot.toVec(), length.toDouble(), periodSteps, phase, thickness)
    }

    /** See [SlidingBlock]. */
    fun slidingBlock(
        from: Point,
        to: Point,
        width: Number,
        height: Number,
        periodSteps: Int,
    ) {
        movers += SlidingBlock(from.toVec(), to.toVec(), width.toDouble(), height.toDouble(), periodSteps)
    }

    fun toHole(): Hole {
        require(greens.isNotEmpty()) { "hole $number has no green" }
        return Hole(
            number = number,
            par = par,
            tee = requireNotNull(tee) { "hole $number has no tee" },
            cup = requireNotNull(cup) { "hole $number has no cup" },
            greens = greens.toList(),
            walls = greens.flatMap { it.edges() } + blocks.flatMap { it.edges() } + looseWalls,
            looseWalls = looseWalls.toList(),
            blocks = blocks.toList(),
            sand = sand.toList(),
            water = water.toList(),
            slopes = slopes.toList(),
            bumpers = bumpers.toList(),
            tunnels = tunnels.toList(),
            movers = movers.toList(),
        )
    }
}
