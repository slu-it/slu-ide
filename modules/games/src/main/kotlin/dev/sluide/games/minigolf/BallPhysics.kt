package dev.sluide.games.minigolf

import dev.sluide.games.minigolf.geometry.Segment
import dev.sluide.games.minigolf.geometry.Vec
import kotlin.math.abs
import kotlin.math.min

/** The ball between two steps. [MiniGolfGame] owns one; [BallPhysics] moves it. */
internal class Ball(
    var position: Vec,
    var velocity: Vec = Vec.ZERO,
) {
    /** The tunnel mouth the ball came out of and has not left yet; it cannot enter that mouth. */
    var blockedMouth: Vec? = null

    /** The centre is over the cup and the cup has already decided (lip-out) for this pass. */
    var overCup = false

    /** Puts the ball at rest at [at], forgetting the tunnel and cup state. */
    fun reset(at: Vec) {
        position = at
        velocity = Vec.ZERO
        blockedMouth = null
        overCup = false
    }
}

internal enum class RollEnd { NONE, REST, HOLED, SPLASH }

/**
 * The physics of one [hole], one step of 1/60 s at a time: no JavaFX, deterministic. A rolling step first applies the
 * slope's pull and the friction of the surface under the centre (sand, slope or felt), then moves the ball in sub-steps
 * of at most [Rules.MAX_SUB_STEP], and after each sub-step resolves collisions and checks water, tunnels and the cup.
 * Collisions are resolved once before the first sub-step too, since a moving obstacle may have moved onto the ball. The
 * ball rests when it is slower than [Rules.REST_SPEED] at the end of a step and not on a slope; resting over the cup
 * holes it.
 *
 * Collisions treat walls and rotating bars as capsules of radius [Rules.BALL_RADIUS] (plus half the bar's thickness):
 * the ball is pushed out along the normal, and the velocity relative to the surface loses its normal component and
 * gets it back reflected with the restitution; a mover's surface velocity comes from its position at the previous
 * step. A sliding block is solid: a ball whose centre is inside it is pushed out on the side the block moves toward
 * (the nearest side when it stands still), or on another side when a wall is in the way. The order is moving
 * obstacles, bumpers, walls, and then sliding blocks once more, so that neither a wall nor a block can leave the ball
 * inside a block.
 */
internal class BallPhysics(
    private val hole: Hole,
) {
    fun onSlope(p: Vec) = hole.slopes.any { it.area.contains(p) }

    fun slopeAcceleration(p: Vec): Vec =
        hole.slopes.filter { it.area.contains(p) }.fold(Vec.ZERO) { sum, slope -> sum + slope.acceleration }

    fun inSand(p: Vec) = hole.sand.any { it.contains(p) }

    fun inWater(p: Vec) = hole.water.any { it.contains(p) }

    /** Rolls [ball] for one step; the moving obstacles stand where they are at [step]. */
    fun roll(
        ball: Ball,
        step: Long,
    ): RollEnd {
        val contacts = moverContacts(step)
        applyForces(ball)
        collide(ball, contacts)
        var remaining = Rules.STEP_SECONDS
        while (remaining > 0.0) {
            val speed = ball.velocity.length
            if (speed == 0.0) break
            val dt = min(remaining, Rules.MAX_SUB_STEP / speed)
            ball.position += ball.velocity * dt
            remaining -= dt
            collide(ball, contacts)
            if (inWater(ball.position)) return RollEnd.SPLASH
            passTunnels(ball)
            if (checkCup(ball)) return RollEnd.HOLED
        }
        if (ball.velocity.length < Rules.REST_SPEED && !onSlope(ball.position)) {
            ball.velocity = Vec.ZERO
            return if (ball.position.distanceTo(hole.cup) <= Rules.CUP_RADIUS) RollEnd.HOLED else RollEnd.REST
        }
        return RollEnd.NONE
    }

    /**
     * Lets the moving obstacles at [step] push the resting [ball]. Returns true when one did and the ball now rolls
     * (at least [Rules.REST_SPEED]); a weaker push only moves it and leaves it at rest.
     */
    fun push(
        ball: Ball,
        step: Long,
    ): Boolean {
        ball.velocity = Vec.ZERO
        val contacts = moverContacts(step)
        var touched = false
        for (contact in contacts) if (contact.resolve(ball)) touched = true
        if (!touched) return false
        collideStatic(ball)
        escapeBlocks(ball, contacts)
        if (ball.velocity.length < Rules.REST_SPEED) {
            ball.velocity = Vec.ZERO
            return false
        }
        return true
    }

    /**
     * After the resting [ball] was put back (water): moves it out of a sliding block that covers the spot at [step],
     * still at rest, and blocks the tunnel mouth it lies in, so that the next shot does not fall into that mouth.
     */
    fun settle(
        ball: Ball,
        step: Long,
    ) {
        escapeBlocks(ball, moverContacts(step))
        ball.velocity = Vec.ZERO
        ball.blockedMouth =
            hole.tunnels
                .flatMap { listOf(it.a, it.b) }
                .firstOrNull { ball.position.distanceTo(it) <= Rules.TUNNEL_RADIUS }
    }

    private fun applyForces(ball: Ball) {
        val p = ball.position
        val velocity = ball.velocity + slopeAcceleration(p) * Rules.STEP_SECONDS
        val deceleration =
            when {
                inSand(p) -> Rules.SAND_DECELERATION
                onSlope(p) -> Rules.SLOPE_DECELERATION
                else -> Rules.FELT_DECELERATION
            } * Rules.STEP_SECONDS
        val speed = velocity.length
        ball.velocity = if (speed <= deceleration) Vec.ZERO else velocity * ((speed - deceleration) / speed)
    }

    private fun collide(
        ball: Ball,
        contacts: List<Contact>,
    ) {
        for (contact in contacts) contact.resolve(ball)
        collideStatic(ball)
        escapeBlocks(ball, contacts)
    }

    private fun escapeBlocks(
        ball: Ball,
        contacts: List<Contact>,
    ) {
        for (contact in contacts) if (contact is BlockContact) contact.escape(ball)
    }

    private fun collideStatic(ball: Ball) {
        for (bumper in hole.bumpers) {
            val reach = bumper.radius + Rules.BALL_RADIUS
            if (!bounce(ball, bumper.center, reach, Rules.BUMPER_RESTITUTION, Vec.ZERO, null)) continue
            val after = ball.velocity.length
            if (after > Rules.MAX_SPEED) ball.velocity = ball.velocity * (Rules.MAX_SPEED / after)
        }
        for (wall in hole.walls) {
            bounce(ball, wall.closestPoint(ball.position), Rules.BALL_RADIUS, Rules.WALL_RESTITUTION, Vec.ZERO, wall)
        }
    }

    /** Whether a ball at [p] keeps clear of every wall and bumper. */
    private fun clearOfStatic(p: Vec) =
        hole.walls.all { it.distanceTo(p) >= Rules.BALL_RADIUS - 1e-9 } &&
            hole.bumpers.all { it.center.distanceTo(p) >= it.radius + Rules.BALL_RADIUS - 1e-9 }

    private fun passTunnels(ball: Ball) {
        val blocked = ball.blockedMouth
        if (blocked != null && ball.position.distanceTo(blocked) > Rules.TUNNEL_RADIUS) ball.blockedMouth = null
        for (tunnel in hole.tunnels) {
            val (exit, direction) =
                when {
                    enters(ball, tunnel.a) -> tunnel.b to tunnel.exitB
                    enters(ball, tunnel.b) -> tunnel.a to tunnel.exitA
                    else -> continue
                }
            ball.position = exit
            ball.velocity = direction * ball.velocity.length
            ball.blockedMouth = exit
            ball.overCup = false
            return
        }
    }

    private fun enters(
        ball: Ball,
        mouth: Vec,
    ) = mouth != ball.blockedMouth && ball.position.distanceTo(mouth) <= Rules.TUNNEL_RADIUS

    /** Returns true when the ball drops; a ball too fast lips out, once per pass over the cup. */
    private fun checkCup(ball: Ball): Boolean {
        val toCup = hole.cup - ball.position
        if (toCup.length > Rules.CUP_RADIUS) {
            ball.overCup = false
            return false
        }
        if (ball.overCup) return false
        ball.overCup = true
        val speed = ball.velocity.length
        if (speed <= Rules.CAPTURE_SPEED) return true
        val direction = ball.velocity / speed
        val turn = StrictMath.atan2(direction cross toCup, direction dot toCup)
        val turned = direction.rotated(turn.coerceIn(-Rules.LIP_OUT_TURN, Rules.LIP_OUT_TURN))
        ball.velocity = turned * (speed * Rules.LIP_OUT_SPEED_FACTOR)
        return false
    }

    private fun moverContacts(step: Long): List<Contact> =
        hole.movers.flatMap { mover ->
            when (mover) {
                is SlidingBlock -> {
                    val center = mover.centerAt(step)
                    val surface = (center - mover.centerAt(step - 1)) / Rules.STEP_SECONDS
                    listOf(BlockContact(center, mover.width / 2, mover.height / 2, surface))
                }

                is RotatingBar -> {
                    val now = mover.segmentsAt(step).single()
                    val before = mover.segmentsAt(step - 1).single()
                    listOf(BarContact(now, before, Rules.BALL_RADIUS + mover.thickness / 2))
                }
            }
        }

    /** A moving obstacle at this step; [resolve] returns whether it touched the ball. */
    private sealed interface Contact {
        fun resolve(ball: Ball): Boolean
    }

    /** A rotating bar at this step and the previous one, for its surface velocity. */
    private inner class BarContact(
        private val now: Segment,
        private val before: Segment,
        private val reach: Double,
    ) : Contact {
        override fun resolve(ball: Ball): Boolean {
            val t = now.closestParameter(ball.position)
            val contact = now.pointAt(t)
            val surface = (contact - before.pointAt(t)) / Rules.STEP_SECONDS
            return bounce(ball, contact, reach, Rules.WALL_RESTITUTION, surface, now)
        }
    }

    /** A solid sliding block at this step, moving at [surface]. */
    private inner class BlockContact(
        private val center: Vec,
        private val halfWidth: Double,
        private val halfHeight: Double,
        private val surface: Vec,
    ) : Contact {
        override fun resolve(ball: Ball): Boolean {
            if (escape(ball)) return true
            val p = ball.position
            val nearest =
                Vec(
                    p.x.coerceIn(center.x - halfWidth, center.x + halfWidth),
                    p.y.coerceIn(center.y - halfHeight, center.y + halfHeight),
                )
            return bounce(ball, nearest, Rules.BALL_RADIUS, Rules.WALL_RESTITUTION, surface, null)
        }

        /** Pushes a ball whose centre is inside the block (or on its edge) out of it; false when it is not inside. */
        fun escape(ball: Ball): Boolean {
            val p = ball.position
            val dx = p.x - center.x
            val dy = p.y - center.y
            if (abs(dx) > halfWidth || abs(dy) > halfHeight) return false
            val r = Rules.BALL_RADIUS
            val exits =
                listOf(
                    Exit(Vec(-1.0, 0.0), dx + halfWidth, Vec(center.x - halfWidth - r, p.y)),
                    Exit(Vec(1.0, 0.0), halfWidth - dx, Vec(center.x + halfWidth + r, p.y)),
                    Exit(Vec(0.0, -1.0), dy + halfHeight, Vec(p.x, center.y - halfHeight - r)),
                    Exit(Vec(0.0, 1.0), halfHeight - dy, Vec(p.x, center.y + halfHeight + r)),
                ).sortedWith(compareBy({ -(it.normal dot surface) }, { it.depth }))
            val exit = exits.firstOrNull { clearOfStatic(it.position) } ?: exits.first()
            ball.position = exit.position
            reflect(ball, exit.normal, Rules.WALL_RESTITUTION, surface)
            return true
        }
    }

    private class Exit(
        val normal: Vec,
        val depth: Double,
        val position: Vec,
    )

    /**
     * Pushes [ball] out to [reach] from [contact] and reflects its velocity relative to [surface] with [restitution].
     * Returns whether they overlapped. [segment] gives the normal when the centre lies exactly on it.
     */
    private fun bounce(
        ball: Ball,
        contact: Vec,
        reach: Double,
        restitution: Double,
        surface: Vec,
        segment: Segment?,
    ): Boolean {
        val offset = ball.position - contact
        val distance = offset.length
        if (distance >= reach) return false
        val normal =
            if (distance > 1e-9) {
                offset / distance
            } else {
                val across = segment?.let { (it.b - it.a).perpendicular().normalized() } ?: Vec(1.0, 0.0)
                if (((ball.velocity - surface) dot across) > 0) -across else across
            }
        ball.position = contact + normal * reach
        reflect(ball, normal, restitution, surface)
        return true
    }

    /** Reflects the velocity relative to [surface] off a surface with outward [normal], when it moves into it. */
    private fun reflect(
        ball: Ball,
        normal: Vec,
        restitution: Double,
        surface: Vec,
    ) {
        val relative = ball.velocity - surface
        val into = relative dot normal
        if (into < 0) ball.velocity = relative - normal * ((1 + restitution) * into) + surface
    }
}
