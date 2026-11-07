package dev.sluide.games.blockbreaker

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The rules of Block Breaker, a paddle-and-bricks game: no JavaFX, not thread-safe, deterministic for a seeded
 * [random]. [step] advances one 1/60 s. Only nextDouble and nextBoolean of the random are used.
 *
 * Interpretations: effect timers run only in PLAYING (effects are cleared whenever play stops); the ball launched in
 * SERVE starts moving in the next step; the tilt of the launch uses the paddle movement of that step, else of the
 * previous one; GameOver is emitted when BALL_LOST ends with no lives left, and only then is the game over; when the
 * paddle width changes (WIDE caught or ended) the centre is kept; when the last ball and the last breakable brick go
 * in the same step, the level counts as cleared; MULTI can turn a shallow ball downwards (a rotation of 30 degrees);
 * the paddle catches a downward ball that overlaps it even slightly below its top, so a ball squeezed against a wall
 * never sticks to or passes through it.
 *
 * `placePaddle`, `clearBalls`, `addBall`, `loadBricks`, `addCapsule`, `addScore`, `setLives`, `setLevel` and
 * `setPaddleHits` are test seams only.
 */
internal class BlockBreakerGame(
    val difficulty: Difficulty,
    private val random: Random,
) {
    var score = 0
        private set
    var lives = difficulty.lives
        private set
    var level = 1
        private set
    var phase = Phase.SERVE
        private set
    val isOver: Boolean get() = phase == Phase.OVER

    var paddleX = (WIDTH - difficulty.paddleWidth) / 2.0
        private set
    val paddleWidth: Double
        get() = difficulty.paddleWidth * if (effectTimers.containsKey(PowerUp.WIDE)) Rules.WIDE_FACTOR else 1.0

    private val grid = BrickGrid().also { it.load(Levels.layout(1)) }
    private val ballList = mutableListOf<Ball>()
    private val capsuleList = mutableListOf<Capsule>()
    private val effectTimers = linkedMapOf<PowerUp, Int>()

    val balls: List<BallState> get() = ballList.map { BallState(it.x, it.y) }
    val bricks: List<BrickState> get() = grid.bricks
    val capsules: List<CapsuleState> get() = capsuleList.map { it.state() }
    val effects: Map<PowerUp, Int> get() = effectTimers.toMap()

    private var paddleHits = 0
    private var phaseTimer = 0
    private var previousDelta = 0.0

    /** Ball speed in px/s now: difficulty, loop factor, paddle-hit speed-up and SLOW. */
    val ballSpeed: Double
        get() {
            val loop = min((level - 1) / Rules.LEVEL_COUNT, 3)
            val speedUp = min(1 + 0.05 * (paddleHits / 10), Rules.MAX_SPEED_UP)
            val slow = if (effectTimers.containsKey(PowerUp.SLOW)) Rules.SLOW_FACTOR else 1.0
            return difficulty.ballSpeed * (1 + 0.1 * loop) * speedUp * slow
        }

    init {
        spawnServeBall()
    }

    /** Test seams. */
    fun placePaddle(x: Double) {
        paddleX = clampPaddle(x)
    }

    fun clearBalls() = ballList.clear()

    fun addBall(
        x: Double,
        y: Double,
        dirX: Double,
        dirY: Double,
    ) = ballList.add(Ball(x, y, dirX, dirY))

    fun loadBricks(rows: List<String>) = grid.load(rows)

    fun addCapsule(
        x: Double,
        y: Double,
        kind: PowerUp,
    ) = capsuleList.add(Capsule(x, y, kind))

    fun addScore(points: Int) {
        score += points
    }

    fun setLives(count: Int) {
        lives = count
    }

    fun setLevel(value: Int) {
        level = value
    }

    fun setPaddleHits(count: Int) {
        paddleHits = count
    }

    fun step(input: Input): List<GameEvent> {
        if (phase == Phase.OVER) return emptyList()
        val events = mutableListOf<GameEvent>()
        val delta = movePaddle(input)
        when (phase) {
            Phase.SERVE -> serve(input, delta)
            Phase.PLAYING -> play(events)
            Phase.BALL_LOST -> if (--phaseTimer == 0) endBallLost(events)
            Phase.LEVEL_BREAK -> if (--phaseTimer == 0) startNextLevel()
            Phase.OVER -> Unit
        }
        previousDelta = delta
        return events
    }

    private fun movePaddle(input: Input): Double {
        val old = paddleX
        val direction = (if (input.right) 1 else 0) - (if (input.left) 1 else 0)
        val pointer = input.pointerX
        val target =
            when {
                direction != 0 -> {
                    paddleX + direction * Rules.PADDLE_KEY_SPEED
                }

                input.left || input.right || pointer == null -> {
                    paddleX
                }

                else -> {
                    paddleX +
                        (pointer - (paddleX + paddleWidth / 2)).coerceIn(
                            -Rules.PADDLE_POINTER_SPEED,
                            Rules.PADDLE_POINTER_SPEED,
                        )
                }
            }
        paddleX = clampPaddle(target)
        return paddleX - old
    }

    private fun clampPaddle(x: Double) = x.coerceIn(Rules.PLAY_LEFT.toDouble(), Rules.PLAY_RIGHT - paddleWidth)

    private fun spawnServeBall() {
        ballList.clear()
        ballList.add(Ball(0.0, 0.0, 0.0, -1.0))
        placeServeBall()
    }

    private fun placeServeBall() {
        ballList.firstOrNull()?.let {
            it.x = paddleX + paddleWidth / 2 - Rules.BALL_SIZE / 2
            it.y = (Rules.PADDLE_TOP - Rules.BALL_SIZE).toDouble()
        }
    }

    private fun serve(
        input: Input,
        delta: Double,
    ) {
        placeServeBall()
        if (!input.launch) return
        val moved = if (delta != 0.0) delta else previousDelta
        val side =
            when {
                moved > 0 -> 1.0
                moved < 0 -> -1.0
                random.nextBoolean() -> 1.0
                else -> -1.0
            }
        val tilt = Math.toRadians(Rules.SERVE_TILT_DEGREES)
        ballList.first().aim(side * sin(tilt), -cos(tilt))
        phase = Phase.PLAYING
    }

    private fun play(events: MutableList<GameEvent>) {
        tickEffects()
        moveCapsules(events)
        val distance = ballSpeed / STEPS_PER_SECOND
        val paddle = PaddleBox(paddleX, paddleWidth)
        for (ball in ballList.toList()) {
            val hits = mutableListOf<BrickHitResult>()
            paddleHits += ball.advance(distance, paddle, grid, hits)
            hits.forEach { handleHit(it, events) }
        }
        val lost = ballList.filter { it.y > Rules.FIELD_BOTTOM }
        ballList.removeAll(lost)
        repeat(lost.size) { events.add(GameEvent.BallLost) }
        if (grid.breakableLeft == 0) {
            clearLevel(events)
        } else if (ballList.isEmpty()) {
            loseLife(events)
        }
    }

    private fun handleHit(
        hit: BrickHitResult,
        events: MutableList<GameEvent>,
    ) {
        if (!hit.destroyed) {
            events.add(GameEvent.BrickHit(hit.column, hit.row))
            return
        }
        score += hit.points
        events.add(GameEvent.BrickDestroyed(hit.column, hit.row, hit.points))
        if (capsuleList.size < Rules.MAX_CAPSULES && random.nextDouble() < difficulty.capsuleChance) {
            capsuleList.add(Capsule.dropFrom(hit.column, hit.row, Capsule.kindFor(random.nextDouble())))
        }
    }

    private fun moveCapsules(events: MutableList<GameEvent>) {
        for (capsule in capsuleList.toList()) {
            capsule.fall()
            if (capsule.overlapsPaddle(paddleX, paddleWidth)) {
                capsuleList.remove(capsule)
                score += Rules.CAPSULE_POINTS
                events.add(GameEvent.PowerUpCaught(capsule.kind))
                applyPowerUp(capsule.kind, events)
            } else if (capsule.isOut) {
                capsuleList.remove(capsule)
            }
        }
    }

    private fun applyPowerUp(
        kind: PowerUp,
        events: MutableList<GameEvent>,
    ) {
        when (kind) {
            PowerUp.WIDE -> {
                keepingCentre { effectTimers[PowerUp.WIDE] = Rules.WIDE_STEPS }
            }

            PowerUp.SLOW -> {
                effectTimers[PowerUp.SLOW] = Rules.SLOW_STEPS
            }

            PowerUp.LIFE -> {
                if (lives < Rules.MAX_LIVES) {
                    lives++
                    events.add(GameEvent.ExtraLife)
                }
            }

            PowerUp.MULTI -> {
                val first = ballList.firstOrNull() ?: return
                for (degrees in listOf(-Rules.MULTI_DEGREES, Rules.MULTI_DEGREES)) {
                    if (ballList.size < Rules.MAX_BALLS) ballList.add(first.copyRotated(degrees))
                }
            }
        }
    }

    private fun tickEffects() {
        for (kind in effectTimers.keys.toList()) {
            val left = (effectTimers[kind] ?: 0) - 1
            when {
                left > 0 -> effectTimers[kind] = left
                kind == PowerUp.WIDE -> keepingCentre { effectTimers.remove(kind) }
                else -> effectTimers.remove(kind)
            }
        }
    }

    private fun keepingCentre(change: () -> Unit) {
        val centre = paddleX + paddleWidth / 2
        change()
        paddleX = clampPaddle(centre - paddleWidth / 2)
    }

    private fun clearEffects() = keepingCentre { effectTimers.clear() }

    private fun loseLife(events: MutableList<GameEvent>) {
        events.add(GameEvent.LifeLost)
        lives--
        clearEffects()
        capsuleList.clear()
        paddleHits = 0
        phase = Phase.BALL_LOST
        phaseTimer = Rules.BALL_LOST_STEPS
    }

    private fun endBallLost(events: MutableList<GameEvent>) {
        if (lives <= 0) {
            phase = Phase.OVER
            events.add(GameEvent.GameOver)
        } else {
            spawnServeBall()
            phase = Phase.SERVE
        }
    }

    private fun clearLevel(events: MutableList<GameEvent>) {
        events.add(GameEvent.LevelCleared(level))
        ballList.clear()
        capsuleList.clear()
        clearEffects()
        phase = Phase.LEVEL_BREAK
        phaseTimer = Rules.LEVEL_BREAK_STEPS
    }

    private fun startNextLevel() {
        level++
        grid.load(Levels.layout(level))
        paddleHits = 0
        spawnServeBall()
        phase = Phase.SERVE
    }

    companion object {
        const val WIDTH = 320
        const val HEIGHT = Rules.FIELD_BOTTOM
        const val STEPS_PER_SECOND = 60
    }
}
