package dev.sluide.games.minigolf

import dev.sluide.games.minigolf.geometry.Segment
import dev.sluide.games.minigolf.geometry.Vec

internal enum class Phase {
    /** The ball rests; [MiniGolfGame.shoot] plays it. Moving obstacles keep moving and may push it. */
    AIMING,

    /** The ball rolls until it rests, drops, splashes or reaches the roll limit. */
    ROLLING,

    /** The ball dropped (or was picked up); the scorecard follows after [Rules.HOLED_STEPS] steps. */
    HOLED,

    /** The scorecard waits for [MiniGolfGame.continueToNext]; nothing moves. */
    CARD,

    /** The last hole's card was left; nothing moves. */
    FINISHED,
}

/**
 * The rules of Mini Golf over a [course] of holes played in order: no JavaFX, not thread-safe, no randomness and no
 * wall clock, so the same calls always give the same round. [step] advances one 1/60 s.
 *
 * Interpretations: the [GameEvent.Shot] of [shoot] is reported by the next [step]. A ball that a moving obstacle pushes
 * hard enough while it rests starts rolling without a stroke (a weaker push only moves it); such a roll is no shot, so
 * it is not recorded as the ghost path. A push never costs a stroke: water after a shot sends the ball back to where
 * the shot was played from with one penalty stroke, water after a push (a roll or a weaker move) sends it back to where
 * it rested before the push, without one. A ball put back is moved out of a sliding block that now covers the spot,
 * and a tunnel mouth it lies in stays blocked for the next shot. The stroke cap applies when any roll ends without
 * holing (rest, splash, roll limit) and the stroke count has reached [Rules.STROKE_CAP], so a splash on the 6th shot
 * (7 strokes) is picked up too. A ball that comes to rest over the cup drops. [stepCount] starts at 0 on every hole, so
 * moving obstacles are in the same place whenever a hole begins.
 *
 * `startAt` and `placeBall` are test seams only.
 */
internal class MiniGolfGame(
    val course: List<Hole> = COURSE,
) {
    init {
        require(course.isNotEmpty()) { "a course needs at least one hole" }
    }

    var phase = Phase.AIMING
        private set
    var holeIndex = 0
        private set
    val hole: Hole get() = course[holeIndex]

    private var physics = BallPhysics(hole)
    private val ballState = Ball(hole.tee)
    val ball: Vec get() = ballState.position
    val ballVelocity: Vec get() = ballState.velocity

    /** False once the ball dropped or was picked up, until the next hole. */
    var ballVisible = true
        private set

    /** Strokes on the current hole, penalties included. */
    var strokes = 0
        private set

    private val scoreList = MutableList<Int?>(course.size) { null }

    /** The score per hole of [course], null while not played. */
    val scores: List<Int?> get() = scoreList.toList()
    val total: Int get() = scoreList.sumOf { it ?: 0 }

    /** The par of the finished holes. */
    val parSoFar: Int get() = course.indices.sumOf { if (scoreList[it] != null) course[it].par else 0 }
    val toPar: Int get() = total - parSoFar

    /** The clock of the moving obstacles: 0 when a hole starts, +1 per step in AIMING, ROLLING and HOLED. */
    var stepCount = 0L
        private set

    /** The previous shot of this hole, sampled every [Rules.GHOST_SAMPLE_STEPS] steps; empty at the start of a hole. */
    var ghostPath: List<Vec> = emptyList()
        private set

    private val recording = mutableListOf<Vec>()

    /** True only while a shot rolls (a counted stroke); false during a push roll and when nothing rolls. */
    var shotInProgress = false
        private set

    /** Where the ball goes back after water: the shot's start, or where it rested before a push. */
    private var rollStart = hole.tee
    private var rollSteps = 0
    private var holedTimer = 0
    private val pending = mutableListOf<GameEvent>()

    fun moverSegments(): List<Segment> = hole.movers.flatMap { it.segmentsAt(stepCount) }

    /** Test seam: starts the hole at [index] afresh (tee, no strokes, no ghost, clock 0); scores are kept. */
    fun startAt(index: Int) {
        require(index in course.indices) { "no hole at index $index" }
        holeIndex = index
        physics = BallPhysics(hole)
        ballState.reset(hole.tee)
        ballVisible = true
        strokes = 0
        ghostPath = emptyList()
        recording.clear()
        shotInProgress = false
        rollStart = hole.tee
        rollSteps = 0
        stepCount = 0
        pending.clear()
        phase = Phase.AIMING
    }

    /** Test seam: the ball rolls from [position] at [velocity], without a stroke; a zero velocity rests next step. */
    fun placeBall(
        position: Vec,
        velocity: Vec = Vec.ZERO,
    ) {
        ballState.reset(position)
        ballState.velocity = velocity
        ballVisible = true
        startRoll(shot = false)
    }

    /**
     * Plays the resting ball at [angle] (radians on the field, y down, 0 = +x) with [power] in 0..1 (up to
     * [Rules.MAX_SPEED]). Counts a stroke and returns true; returns false outside AIMING.
     */
    fun shoot(
        angle: Double,
        power: Double,
    ): Boolean {
        if (phase != Phase.AIMING) return false
        ballState.velocity = Vec.ofAngle(angle, power.coerceIn(0.0, 1.0) * Rules.MAX_SPEED)
        strokes++
        pending += GameEvent.Shot(strokes)
        startRoll(shot = true)
        return true
    }

    fun step(): List<GameEvent> {
        val events = pending.toMutableList()
        pending.clear()
        when (phase) {
            Phase.AIMING -> {
                stepCount++
                aim(events)
            }

            Phase.ROLLING -> {
                stepCount++
                roll(events)
            }

            Phase.HOLED -> {
                stepCount++
                if (--holedTimer <= 0) phase = Phase.CARD
            }

            Phase.CARD, Phase.FINISHED -> {
                Unit
            }
        }
        return events
    }

    /** Leaves the scorecard: the next hole in AIMING, or FINISHED with [GameEvent.RoundFinished] after the last. */
    fun continueToNext(): List<GameEvent> {
        if (phase != Phase.CARD) return emptyList()
        if (holeIndex == course.lastIndex) {
            phase = Phase.FINISHED
            return listOf(GameEvent.RoundFinished(total))
        }
        startAt(holeIndex + 1)
        return emptyList()
    }

    /** A resting ball may be pushed by a moving obstacle: into a roll, or a little, or into the water. */
    private fun aim(events: MutableList<GameEvent>) {
        val rested = ballState.position
        val rolls = physics.push(ballState, stepCount)
        if (physics.inWater(ballState.position)) {
            events += GameEvent.Splash
            ballState.reset(rested)
            physics.settle(ballState, stepCount)
        } else if (rolls) {
            startRoll(shot = false, from = rested)
        }
    }

    private fun startRoll(
        shot: Boolean,
        from: Vec = ballState.position,
    ) {
        shotInProgress = shot
        rollStart = from
        rollSteps = 0
        recording.clear()
        if (shot) recording += ballState.position
        phase = Phase.ROLLING
    }

    private fun roll(events: MutableList<GameEvent>) {
        rollSteps++
        when (physics.roll(ballState, stepCount)) {
            RollEnd.NONE -> {
                if (shotInProgress && rollSteps % Rules.GHOST_SAMPLE_STEPS == 0) recording += ballState.position
                if (rollSteps >= Rules.ROLL_LIMIT_STEPS) {
                    ballState.velocity = Vec.ZERO
                    endRoll(events)
                }
            }

            RollEnd.REST -> {
                endRoll(events)
            }

            RollEnd.SPLASH -> {
                if (shotInProgress) strokes++
                endRecording()
                events += GameEvent.Splash
                ballState.reset(rollStart)
                physics.settle(ballState, stepCount)
                endRoll(events)
            }

            RollEnd.HOLED -> {
                endRecording()
                ballState.reset(hole.cup)
                finishHole(strokes)
                events += GameEvent.Holed(strokes, strokes == 1)
                events += GameEvent.HoleFinished(holeIndex, strokes)
                holedTimer = if (strokes == 1) Rules.HOLE_IN_ONE_STEPS else Rules.HOLED_STEPS
            }
        }
    }

    /** A roll ended without holing: the cap, or the next shot. */
    private fun endRoll(events: MutableList<GameEvent>) {
        endRecording()
        if (strokes >= Rules.STROKE_CAP) {
            finishHole(Rules.PICKED_UP_SCORE)
            events += GameEvent.PickedUp
            events += GameEvent.HoleFinished(holeIndex, Rules.PICKED_UP_SCORE)
            holedTimer = Rules.HOLED_STEPS
        } else {
            phase = Phase.AIMING
        }
    }

    private fun endRecording() {
        if (!shotInProgress) return
        if (recording.last() != ballState.position) recording += ballState.position
        ghostPath = recording.toList()
        shotInProgress = false
    }

    private fun finishHole(score: Int) {
        scoreList[holeIndex] = score
        ballVisible = false
        phase = Phase.HOLED
    }
}
