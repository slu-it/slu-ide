package dev.sluide.games.minigolf

import dev.sluide.games.minigolf.geometry.Polygon
import dev.sluide.games.minigolf.geometry.Segment
import dev.sluide.games.minigolf.geometry.Vec

/**
 * The structural checks of a hole for CourseTest. Each returns the problems it finds, empty when the hole passes, so
 * that a test can show each one failing on a deliberately bad hole.
 */
internal object CourseChecks {
    fun margin(hole: Hole): List<String> {
        val xs = Rules.HOLE_MARGIN..Rules.FIELD_WIDTH - Rules.HOLE_MARGIN
        val ys = Rules.HUD_HEIGHT + Rules.HOLE_MARGIN..Rules.FIELD_HEIGHT - Rules.HOLE_MARGIN
        return hole.greens
            .flatMap { it.points }
            .filterNot { it.x in xs && it.y in ys }
            .map { "green point $it is within the margin" }
    }

    /** Greens neither overlap nor touch, since each outline is a rail. */
    fun greens(hole: Hole): List<String> {
        val problems = mutableListOf<String>()
        for (i in hole.greens.indices) {
            for (j in i + 1 until hole.greens.size) {
                val (a, b) = hole.greens[i] to hole.greens[j]
                val apart = a.edges().minOf { edge -> b.edges().minOf { distance(edge, it) } }
                val inside = a.points.any { b.contains(it) } || b.points.any { a.contains(it) }
                if (apart < 1.0 || inside) problems += "greens $i and $j overlap or touch"
            }
        }
        return problems
    }

    fun teeAndCup(hole: Hole): List<String> {
        val physics = BallPhysics(hole)
        val problems = mutableListOf<String>()
        for ((name, p) in listOf("tee" to hole.tee, "cup" to hole.cup)) {
            if (!onFelt(hole, p)) problems += "$name $p is not on a green or inside a block"
            if (physics.inSand(p)) problems += "$name $p is in sand"
            if (physics.inWater(p)) problems += "$name $p is in water"
            if (clearance(hole, p) < Rules.BALL_RADIUS + 1) problems += "$name $p is too close to a wall"
        }
        if (physics.onSlope(hole.tee)) problems += "the tee is on a slope"
        return problems
    }

    /** Mouths lie on clear felt (no water, no sand, away from walls), apart, with unit exit directions. */
    fun tunnels(hole: Hole): List<String> {
        val physics = BallPhysics(hole)
        val problems = mutableListOf<String>()
        hole.tunnels.forEach { tunnel ->
            for (mouth in listOf(tunnel.a, tunnel.b)) {
                if (!onFelt(hole, mouth)) problems += "mouth $mouth is not on felt"
                if (physics.inWater(mouth) || physics.inSand(mouth)) problems += "mouth $mouth is in water or sand"
                if (clearance(hole, mouth) < Rules.BALL_RADIUS + 1) problems += "mouth $mouth is too close to a wall"
            }
            if (tunnel.a.distanceTo(tunnel.b) <= 2 * Rules.TUNNEL_RADIUS) problems += "the mouths of $tunnel overlap"
            if (Math.abs(tunnel.exitA.length - 1) > 1e-9 || Math.abs(tunnel.exitB.length - 1) > 1e-9) {
                problems += "an exit direction of $tunnel is no unit vector"
            }
        }
        return problems
    }

    /** No point of a mover moves by the ball radius or more in one step, or it could jump over a resting ball. */
    fun moverSpeed(hole: Hole): List<String> =
        hole.movers.flatMap { mover ->
            (0L until period(mover)).mapNotNull { step ->
                val moved =
                    mover.segmentsAt(step).zip(mover.segmentsAt(step + 1)).maxOf { (a, b) ->
                        maxOf(a.a.distanceTo(b.a), a.b.distanceTo(b.b))
                    }
                if (moved < Rules.BALL_RADIUS) null else "$mover moves $moved at step $step"
            }
        }

    /** No mover comes near the tee, nor covers it, at any step. */
    fun moverTee(hole: Hole): List<String> =
        hole.movers.flatMap { mover ->
            val reach = Rules.BALL_RADIUS + mover.thickness / 2 + 1
            (0L until period(mover)).mapNotNull { step ->
                val near = mover.segmentsAt(step).any { it.distanceTo(hole.tee) < reach }
                val covers = mover is SlidingBlock && blockDistance(mover, step, hole.tee) < Rules.BALL_RADIUS + 1
                if (near || covers) "$mover reaches the tee at step $step" else null
            }
        }

    /**
     * Every mover keeps room for a ball to every wall, bumper and water, so that it can neither pin a ball nor push one
     * into the water again and again (a push into water puts the ball back without a penalty).
     */
    fun moverWalls(hole: Hole): List<String> =
        hole.movers.flatMap { mover ->
            val room = 2 * Rules.BALL_RADIUS + mover.thickness / 2
            (0L until period(mover)).mapNotNull { step ->
                val segments = mover.segmentsAt(step)
                val toWalls = segments.minOf { segment -> hole.walls.minOf { distance(segment, it) } }
                val toBumpers =
                    hole.bumpers.minOfOrNull { bumper ->
                        segments.minOf { it.distanceTo(bumper.center) } - bumper.radius
                    } ?: Double.MAX_VALUE
                val toWater = hole.water.minOfOrNull { waterDistance(segments, it) } ?: Double.MAX_VALUE
                val nearest = minOf(toWalls, toBumpers, toWater)
                if (nearest < room) "$mover comes within $nearest of a wall, bumper or water" else null
            }
        }

    /** The distance between a mover's [segments] and a [water] polygon, 0 when they overlap. */
    private fun waterDistance(
        segments: List<Segment>,
        water: Polygon,
    ): Double {
        val endsInWater = segments.any { water.contains(it.a) || water.contains(it.b) }
        val outline = segments.map { it.a }
        val coversWater = outline.size >= 3 && water.points.any { Polygon(outline).contains(it) }
        if (endsInWater || coversWater) return 0.0
        return segments.minOf { segment -> water.edges().minOf { distance(segment, it) } }
    }

    /** A resting ball left anywhere on a slope rolls off it: it rests on flat felt, drops or splashes. */
    fun slopesDrain(hole: Hole): List<String> {
        val physics = BallPhysics(hole)
        val game = MiniGolfGame(listOf(hole))
        val problems = mutableListOf<String>()
        for (slope in hole.slopes) {
            for (p in grid(slope.area)) {
                if (!onFelt(hole, p) || physics.inWater(p) || clearance(hole, p) < Rules.BALL_RADIUS) continue
                game.startAt(0)
                game.placeBall(p)
                val events = mutableListOf<GameEvent>()
                var steps = 0
                while (game.phase == Phase.ROLLING && steps < Rules.ROLL_LIMIT_STEPS - 1) {
                    events += game.step()
                    steps++
                }
                val drained =
                    game.phase == Phase.HOLED ||
                        GameEvent.Splash in events ||
                        (game.phase == Phase.AIMING && !physics.onSlope(game.ball))
                if (!drained) problems += "a ball left at $p ends ${game.phase} at ${game.ball}"
            }
        }
        return problems
    }

    fun onFelt(
        hole: Hole,
        p: Vec,
    ) = hole.greens.any { it.contains(p) } && hole.blocks.none { it.contains(p) }

    /** The clearance from [p] to the nearest wall or bumper edge. */
    private fun clearance(
        hole: Hole,
        p: Vec,
    ) = minOf(
        hole.walls.minOf { it.distanceTo(p) },
        hole.bumpers.minOfOrNull { it.center.distanceTo(p) - it.radius } ?: Double.MAX_VALUE,
    )

    private fun period(mover: Mover): Long =
        when (mover) {
            is RotatingBar -> Math.abs(mover.periodSteps).toLong()
            is SlidingBlock -> mover.periodSteps.toLong()
        }

    /** The distance from [p] to the block's rectangle at [step], 0 inside. */
    private fun blockDistance(
        block: SlidingBlock,
        step: Long,
        p: Vec,
    ): Double {
        val center = block.centerAt(step)
        val dx = maxOf(Math.abs(p.x - center.x) - block.width / 2, 0.0)
        val dy = maxOf(Math.abs(p.y - center.y) - block.height / 2, 0.0)
        return Math.sqrt(dx * dx + dy * dy)
    }

    private fun distance(
        s: Segment,
        t: Segment,
    ): Double {
        val ds = s.b - s.a
        val dt = t.b - t.a
        val crosses =
            (ds cross (t.a - s.a)) * (ds cross (t.b - s.a)) < 0 && (dt cross (s.a - t.a)) * (dt cross (s.b - t.a)) < 0
        if (crosses) return 0.0
        return minOf(s.distanceTo(t.a), s.distanceTo(t.b), t.distanceTo(s.a), t.distanceTo(s.b))
    }

    /** Points every 6 units inside [area]. */
    private fun grid(area: Polygon): List<Vec> {
        val points = area.points
        return (points.minOf { it.x }.toInt()..points.maxOf { it.x }.toInt() step 6)
            .flatMap { x ->
                (points.minOf { it.y }.toInt()..points.maxOf { it.y }.toInt() step 6).map { y ->
                    Vec(x.toDouble(), y.toDouble())
                }
            }.filter { area.contains(it) }
    }
}
