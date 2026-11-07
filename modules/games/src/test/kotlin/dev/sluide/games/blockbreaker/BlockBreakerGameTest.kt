package dev.sluide.games.blockbreaker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

internal class BlockBreakerGameTest {
    /** One far brick in the top right corner, so nothing clears the level by accident. */
    private val anchor = listOf("............1")

    private fun newGame(
        difficulty: Difficulty = Difficulty.NORMAL,
        random: Random = FixedRandom(),
    ) = BlockBreakerGame(difficulty, random)

    /** A game in PLAYING without balls on a field of [rows]; the paddle is at 140 (NORMAL). */
    private fun playing(
        difficulty: Difficulty = Difficulty.NORMAL,
        random: Random = FixedRandom(),
        rows: List<String> = anchor,
    ) = newGame(difficulty, random).also {
        it.loadBricks(rows)
        it.step(Input(launch = true))
        it.clearBalls()
    }

    /** Steps [count] times with one ball flying up in the free middle of the field. */
    private fun BlockBreakerGame.stepAlive(count: Int): List<GameEvent> =
        (1..count).flatMap {
            clearBalls()
            addBall(200.0, 100.0, 0.0, -1.0)
            step(Input())
        }

    /** Sends a ball up into the brick at [column] and [row] (bottom edge known) and steps once. */
    private fun BlockBreakerGame.hitFromBelow(
        column: Int,
        row: Int,
    ): List<GameEvent> {
        clearBalls()
        addBall(4.0 + column * 24 + 10, 32.0 + row * 8 + 8 + 1, 0.0, -1.0)
        return step(Input())
    }

    private fun BlockBreakerGame.loseBall(): List<GameEvent> {
        clearBalls()
        addBall(100.0, 239.0, 0.0, 1.0)
        return step(Input())
    }

    private fun BlockBreakerGame.catch(kind: PowerUp) = run { addCapsule(paddleX + 10, 214.0, kind) }

    // paddle

    @ParameterizedTest
    @CsvSource("true,4", "false,276")
    fun `the paddle stops at the walls`(
        left: Boolean,
        expectedX: Double,
    ) {
        val game = newGame()
        repeat(100) { game.step(Input(left = left, right = !left)) }
        assertEquals(expectedX, game.paddleX)
    }

    @Test
    fun `keys move the paddle four pixels a step and both keys cancel`() {
        val game = newGame()
        game.step(Input(right = true))
        assertEquals(144.0, game.paddleX)
        game.step(Input(left = true))
        game.step(Input(left = true))
        assertEquals(136.0, game.paddleX)
        game.step(Input(left = true, right = true))
        assertEquals(136.0, game.paddleX)
    }

    @Test
    fun `the pointer moves the paddle centre by at most ten pixels a step`() {
        val game = newGame()
        game.placePaddle(100.0)
        game.step(Input(pointerX = 300.0))
        assertEquals(110.0, game.paddleX)
        game.step(Input(pointerX = 135.0))
        assertEquals(115.0, game.paddleX)
        game.step(Input(pointerX = 135.0))
        assertEquals(115.0, game.paddleX)
    }

    @Test
    fun `keys win over the pointer`() {
        val game = newGame()
        game.step(Input(right = true, pointerX = 0.0))
        assertEquals(144.0, game.paddleX)
    }

    @Test
    fun `the paddle moves after losing a ball but not when the game is over`() {
        val game = playing()
        game.setLives(1)
        game.loseBall()
        assertEquals(Phase.BALL_LOST, game.phase)
        game.step(Input(right = true))
        assertEquals(144.0, game.paddleX)
        repeat(60) { game.step(Input()) }
        assertTrue(game.isOver)
        game.step(Input(right = true))
        assertEquals(144.0, game.paddleX)
    }

    // serve

    @Test
    fun `the serve ball rests on the paddle and follows it`() {
        val game = newGame()
        assertEquals(Phase.SERVE, game.phase)
        assertEquals(BallState(158.0, 216.0), game.balls.single())
        game.step(Input(right = true))
        assertEquals(BallState(162.0, 216.0), game.balls.single())
    }

    @ParameterizedTest
    @CsvSource(
        "false,false,false,-1",
        "false,false,true,1",
        "true,false,false,1",
        "false,true,true,-1",
    )
    fun `the launch tilts towards the paddle movement, else to a random side`(
        right: Boolean,
        left: Boolean,
        randomSide: Boolean,
        expectedSign: Int,
    ) {
        val game = newGame(random = FixedRandom(bool = randomSide))
        game.step(Input(right = right, left = left, launch = true))
        assertEquals(Phase.PLAYING, game.phase)
        val before = game.balls.single()
        game.step(Input())
        val after = game.balls.single()
        val speed = 150.0 / 60
        assertEquals(expectedSign * speed * sin(Math.toRadians(15.0)), after.x - before.x, 1e-6)
        assertTrue(after.y < before.y)
    }

    @Test
    fun `the launch uses the movement of the previous step when the paddle stands still`() {
        val game = newGame(random = FixedRandom(bool = false))
        game.step(Input(right = true))
        game.step(Input(launch = true))
        val before = game.balls.single()
        game.step(Input())
        assertTrue(game.balls.single().x > before.x)
    }

    // speed

    @ParameterizedTest
    @CsvSource("0,150.0", "9,150.0", "10,157.5", "50,187.5", "120,240.0", "200,240.0")
    fun `the speed grows by five percent per ten paddle hits up to sixty percent`(
        hits: Int,
        speed: Double,
    ) {
        val game = newGame()
        game.setPaddleHits(hits)
        assertEquals(speed, game.ballSpeed, 1e-9)
    }

    @ParameterizedTest
    @CsvSource("1,150.0", "10,150.0", "11,165.0", "21,180.0", "31,195.0", "41,195.0")
    fun `every ten levels the speed rises by ten percent up to thirty`(
        level: Int,
        speed: Double,
    ) {
        val game = newGame()
        game.setLevel(level)
        assertEquals(speed, game.ballSpeed, 1e-9)
    }

    @Test
    fun `a paddle bounce counts as a hit and a lost life resets the count`() {
        val game = playing()
        game.setPaddleHits(9)
        game.addBall(game.paddleX + 18, 216.0, 0.0, 1.0)
        game.step(Input())
        assertEquals(157.5, game.ballSpeed, 1e-9)
        game.loseBall()
        assertEquals(150.0, game.ballSpeed, 1e-9)
    }

    @Test
    fun `a new level resets the paddle hit count`() {
        val game = playing(rows = listOf("1............"))
        game.setPaddleHits(30)
        game.hitFromBelow(0, 0)
        repeat(90) { game.step(Input()) }
        assertEquals(Phase.SERVE, game.phase)
        assertEquals(150.0, game.ballSpeed, 1e-9)
    }

    @Test
    fun `the speed tops out at hard difficulty after three loops and a full speed-up`() {
        val game = newGame(Difficulty.HARD)
        game.setLevel(31)
        game.setPaddleHits(120)
        assertEquals(374.4, game.ballSpeed, 1e-9)
    }

    // bricks

    @ParameterizedTest
    @CsvSource("T,2,60", "H,3,90", "1,1,10", "3,1,30", "5,1,50")
    fun `bricks take their hits and give their points`(
        char: Char,
        hits: Int,
        points: Int,
    ) {
        val game = playing(rows = listOf("$char............") + anchorRows)
        repeat(hits - 1) {
            assertEquals(listOf<GameEvent>(GameEvent.BrickHit(0, 0)), game.hitFromBelow(0, 0))
            assertEquals(hits - 1 - it, game.bricks.first { b -> b.column == 0 && b.row == 0 }.hitsLeft)
            assertEquals(0, game.score)
        }
        assertEquals(listOf<GameEvent>(GameEvent.BrickDestroyed(0, 0, points)), game.hitFromBelow(0, 0))
        assertEquals(points, game.score)
        assertTrue(game.bricks.none { it.column == 0 && it.row == 0 })
    }

    private val gap = "............."
    private val anchorRows = listOf(gap, "............1")

    @Test
    fun `steel is hit but never breaks`() {
        val game = playing(rows = listOf("#............") + anchorRows)
        repeat(5) { assertEquals(listOf<GameEvent>(GameEvent.BrickHit(0, 0)), game.hitFromBelow(0, 0)) }
        assertEquals(0, game.score)
        assertEquals(BrickKind.STEEL, game.bricks.first { it.column == 0 && it.row == 0 }.kind)
    }

    @Test
    fun `brick states carry kind, colour tier and hits left`() {
        val game = newGame()
        game.loadBricks(listOf("3TH#........."))
        assertEquals(
            listOf(
                BrickState(0, 0, BrickKind.NORMAL, 3, 1),
                BrickState(1, 0, BrickKind.TOUGH, 0, 2),
                BrickState(2, 0, BrickKind.HARD, 0, 3),
                BrickState(3, 0, BrickKind.STEEL, 0, 0),
            ),
            game.bricks,
        )
    }

    // losing balls

    @Test
    fun `losing the last ball costs a life, clears effects and capsules, then serves again`() {
        val game = playing()
        game.catch(PowerUp.SLOW)
        game.stepAlive(1)
        assertTrue(PowerUp.SLOW in game.effects)
        game.addCapsule(10.0, 100.0, PowerUp.WIDE)
        val events = game.loseBall()
        assertEquals(listOf(GameEvent.BallLost, GameEvent.LifeLost), events)
        assertEquals(2, game.lives)
        assertTrue(game.effects.isEmpty())
        assertTrue(game.capsules.isEmpty())
        assertEquals(Phase.BALL_LOST, game.phase)
        repeat(59) { assertTrue(game.step(Input()).isEmpty()) }
        assertEquals(Phase.BALL_LOST, game.phase)
        game.step(Input())
        assertEquals(Phase.SERVE, game.phase)
        assertEquals(1, game.balls.size)
    }

    @Test
    fun `losing one of several balls only reports the ball`() {
        val game = playing()
        game.addBall(200.0, 100.0, 0.0, -1.0)
        game.addBall(100.0, 239.0, 0.0, 1.0)
        assertEquals(listOf<GameEvent>(GameEvent.BallLost), game.step(Input()))
        assertEquals(Phase.PLAYING, game.phase)
        assertEquals(1, game.balls.size)
    }

    @Test
    fun `losing the last life ends the game and later steps do nothing`() {
        val game = playing()
        game.setLives(1)
        game.addScore(70)
        game.loseBall()
        assertEquals(0, game.lives)
        assertFalse(game.isOver)
        val events = (1..60).flatMap { game.step(Input()) }
        assertEquals(listOf<GameEvent>(GameEvent.GameOver), events)
        assertTrue(game.isOver)
        assertEquals(Phase.OVER, game.phase)
        assertTrue(game.step(Input(launch = true, right = true)).isEmpty())
        assertEquals(70, game.score)
    }

    // power-ups

    @Test
    fun `a caught capsule gives fifty points and falls one pixel a step`() {
        val game = playing()
        game.addCapsule(10.0, 100.0, PowerUp.WIDE)
        game.stepAlive(1)
        assertEquals(CapsuleState(10.0, 101.0, PowerUp.WIDE), game.capsules.single())
        game.catch(PowerUp.SLOW)
        assertEquals(listOf<GameEvent>(GameEvent.PowerUpCaught(PowerUp.SLOW)), game.stepAlive(1))
        assertEquals(50, game.score)
    }

    @Test
    fun `a capsule that passes the field bottom is removed`() {
        val game = playing()
        game.addCapsule(10.0, 240.0, PowerUp.WIDE)
        game.stepAlive(1)
        assertTrue(game.capsules.isEmpty())
        assertEquals(0, game.score)
    }

    @Test
    fun `WIDE widens the paddle by half around its centre for 1200 steps and a second one restarts the timer`() {
        val game = playing()
        game.catch(PowerUp.WIDE)
        game.stepAlive(1)
        assertEquals(60.0, game.paddleWidth)
        assertEquals(130.0, game.paddleX)
        assertEquals(1200, game.effects[PowerUp.WIDE])
        game.stepAlive(600)
        game.catch(PowerUp.WIDE)
        game.stepAlive(1)
        assertEquals(1200, game.effects[PowerUp.WIDE])
        game.stepAlive(1199)
        assertEquals(1, game.effects[PowerUp.WIDE])
        assertEquals(60.0, game.paddleWidth)
        game.stepAlive(1)
        assertTrue(game.effects.isEmpty())
        assertEquals(40.0, game.paddleWidth)
        assertEquals(140.0, game.paddleX)
    }

    @ParameterizedTest
    @CsvSource("4,4", "276,256")
    fun `WIDE at a wall keeps the paddle inside`(
        before: Double,
        after: Double,
    ) {
        val game = playing()
        game.placePaddle(before)
        game.catch(PowerUp.WIDE)
        game.stepAlive(1)
        assertEquals(60.0, game.paddleWidth)
        assertEquals(after, game.paddleX)
    }

    @ParameterizedTest
    @CsvSource("1,3", "3,5", "4,5", "5,5")
    fun `MULTI adds two balls rotated by thirty degrees up to five`(
        start: Int,
        expected: Int,
    ) {
        val game = playing()
        repeat(start) { game.addBall(200.0 + it * 8, 100.0, 0.0, -1.0) }
        game.catch(PowerUp.MULTI)
        game.step(Input())
        assertEquals(expected, game.balls.size)
        if (start == 1) {
            val offsets = game.balls.map { it.x - 200.0 }.sorted()
            val side = 150.0 / 60 * sin(Math.toRadians(30.0))
            assertEquals(-side, offsets[0], 1e-6)
            assertEquals(0.0, offsets[1], 1e-6)
            assertEquals(side, offsets[2], 1e-6)
        }
    }

    @Test
    fun `SLOW cuts the speed by thirty percent for 900 steps and a second one restarts it`() {
        val game = playing()
        game.catch(PowerUp.SLOW)
        game.stepAlive(1)
        assertEquals(105.0, game.ballSpeed, 1e-9)
        assertEquals(900, game.effects[PowerUp.SLOW])
        game.stepAlive(100)
        game.catch(PowerUp.SLOW)
        game.stepAlive(1)
        assertEquals(900, game.effects[PowerUp.SLOW])
        game.stepAlive(899)
        assertEquals(1, game.effects[PowerUp.SLOW])
        game.stepAlive(1)
        assertTrue(game.effects.isEmpty())
        assertEquals(150.0, game.ballSpeed, 1e-9)
    }

    @ParameterizedTest
    @CsvSource("8,9,true", "9,9,false")
    fun `LIFE adds a life up to nine`(
        lives: Int,
        expected: Int,
        extra: Boolean,
    ) {
        val game = playing()
        game.setLives(lives)
        game.catch(PowerUp.LIFE)
        val events = game.stepAlive(1)
        assertEquals(expected, game.lives)
        assertEquals(extra, GameEvent.ExtraLife in events)
        assertTrue(GameEvent.PowerUpCaught(PowerUp.LIFE) in events)
    }

    @Test
    fun `a destroyed brick drops a capsule centred on it with the rolled kind`() {
        val game = playing(random = FixedRandom(listOf(0.0, 0.95)), rows = listOf(gap, "..1..........") + anchorRows)
        game.hitFromBelow(2, 1)
        assertEquals(CapsuleState(58.0, 41.0, PowerUp.LIFE), game.capsules.single())
    }

    @ParameterizedTest
    @CsvSource(
        "EASY,0.149,true",
        "EASY,0.15,false",
        "NORMAL,0.119,true",
        "NORMAL,0.12,false",
        "HARD,0.099,true",
        "HARD,0.1,false",
    )
    fun `the capsule chance depends on the difficulty`(
        difficulty: Difficulty,
        roll: Double,
        drops: Boolean,
    ) {
        val game = playing(difficulty, FixedRandom(listOf(roll, 0.0)), listOf("1............") + anchorRows)
        game.hitFromBelow(0, 0)
        assertEquals(drops, game.capsules.isNotEmpty())
    }

    @Test
    fun `at most two capsules fall at once`() {
        val game = playing(random = FixedRandom(listOf(0.0)), rows = listOf("1............") + anchorRows)
        game.addCapsule(10.0, 100.0, PowerUp.WIDE)
        game.addCapsule(50.0, 100.0, PowerUp.WIDE)
        game.hitFromBelow(0, 0)
        assertEquals(2, game.capsules.size)
    }

    // level

    @Test
    fun `clearing the level ignores steel, takes a break and loads the next layout`() {
        val game = playing(rows = listOf("#1...........", "............."))
        game.catch(PowerUp.SLOW)
        game.stepAlive(1)
        game.addCapsule(10.0, 100.0, PowerUp.WIDE)
        val events = game.hitFromBelow(1, 0)
        assertEquals(listOf(GameEvent.BrickDestroyed(1, 0, 10), GameEvent.LevelCleared(1)), events)
        assertEquals(Phase.LEVEL_BREAK, game.phase)
        assertTrue(game.balls.isEmpty() && game.capsules.isEmpty() && game.effects.isEmpty())
        repeat(89) { game.step(Input()) }
        assertEquals(Phase.LEVEL_BREAK, game.phase)
        game.step(Input())
        assertEquals(Phase.SERVE, game.phase)
        assertEquals(2, game.level)
        assertEquals(1, game.balls.size)
        assertEquals(Levels.layout(2).sumOf { row -> row.count { it != '.' } }, game.bricks.size)
        assertEquals(0, game.bricks.first().row)
    }

    @Test
    fun `level eleven plays the first layout again with the faster ball`() {
        val game = playing(rows = listOf("1............"))
        game.setLevel(10)
        game.hitFromBelow(0, 0)
        repeat(90) { game.step(Input()) }
        assertEquals(11, game.level)
        assertEquals(Levels.layout(1).sumOf { row -> row.count { it != '.' } }, game.bricks.size)
        assertEquals(165.0, game.ballSpeed, 1e-9)
    }

    @ParameterizedTest
    @EnumSource(Difficulty::class)
    fun `a new game starts in serve on level one with the difficulty's lives`(difficulty: Difficulty) {
        val game = newGame(difficulty)
        assertEquals(Phase.SERVE, game.phase)
        assertEquals(1, game.level)
        assertEquals(difficulty.lives, game.lives)
        assertEquals(difficulty.paddleWidth.toDouble(), game.paddleWidth)
        assertEquals(Levels.layout(1).sumOf { row -> row.count { it != '.' } }, game.bricks.size)
        assertTrue(abs(game.paddleX + game.paddleWidth / 2 - 160.0) < 1e-9)
    }
}
