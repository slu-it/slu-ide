package dev.sluide.games.orbitguard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import kotlin.random.Random

internal class OrbitGuardGameTest {
    /** Never fires (nextDouble 1.0), bonus countdown 1200, bonus from the left. */
    private fun quietGame(difficulty: Difficulty = Difficulty.NORMAL) = OrbitGuardGame(difficulty, ScriptedRandom())

    private fun enemy(
        x: Double,
        y: Double,
        kind: EnemyKind = EnemyKind.SCOUT,
        column: Int = 0,
        row: Int = 0,
    ) = FleetEnemy(kind, column, row, x, y)

    /** An enemy parked in the top left, so killing another one does not clear the wave. */
    private fun anchor() = enemy(10.0, 40.0)

    private fun fleetOf(vararg enemies: FleetEnemy) = Fleet(enemies.toList(), 1.0)

    private fun OrbitGuardGame.stepTimes(
        count: Int,
        input: Input = Input(),
    ) = (1..count).flatMap { step(input) }

    private fun OrbitGuardGame.hitShip(): List<GameEvent> {
        addEnemyShot(shipX + 6, 212.0)
        return step(Input())
    }

    @Test
    fun `ship clamps at both edges`() {
        val game = quietGame()
        game.placeShip(5.0)
        game.stepTimes(3, Input(left = true))
        assertEquals(4.0, game.shipX)
        game.placeShip(302.0)
        game.stepTimes(3, Input(right = true))
        assertEquals(303.0, game.shipX)
    }

    @Test
    fun `ship moves 1_5 px per step and stays with left and right together`() {
        val game = quietGame()
        game.step(Input(right = true))
        assertEquals(155.0, game.shipX)
        game.step(Input(left = true, right = true))
        assertEquals(155.0, game.shipX)
        game.step(Input(left = true))
        assertEquals(153.5, game.shipX)
    }

    @Test
    fun `held fire repeats after the cooldown only`() {
        val game = quietGame()
        game.fleet = fleetOf(anchor())
        val fire = Input(fire = true)
        game.stepTimes(15, fire)
        assertEquals(1, game.playerShots.size)
        game.step(fire)
        assertEquals(2, game.playerShots.size)
        assertEquals(ShotState(159.5, 212.0), game.playerShots.last())
    }

    @Test
    fun `at most three player shots fly even when the cooldown is over`() {
        val game = quietGame()
        game.fleet = fleetOf(anchor())
        game.stepTimes(46, Input(fire = true))
        assertEquals(3, game.playerShots.size)
    }

    @ParameterizedTest
    @EnumSource(EnemyKind::class)
    fun `a shot kills an enemy and scores its points`(kind: EnemyKind) {
        val game = quietGame()
        val target = enemy(100.0, 100.0, kind, column = 5)
        game.fleet = fleetOf(anchor(), target)
        game.addPlayerShot(target.x + 2, target.y + 10)
        val events = game.step(Input())
        assertEquals(listOf<GameEvent>(GameEvent.EnemyDestroyed(kind, kind.points)), events)
        assertEquals(kind.points, game.score)
        assertEquals(1, game.enemies.size)
        assertTrue(game.playerShots.isEmpty())
        assertEquals(ExplosionKind.ENEMY, game.explosions.single().kind)
    }

    @Test
    fun `a shot leaving the top ends with an explosion`() {
        val game = quietGame()
        game.fleet = fleetOf(anchor())
        game.addPlayerShot(200.0, 16.0)
        game.step(Input())
        assertEquals(12.0, game.playerShots.single().y)
        game.step(Input())
        assertTrue(game.playerShots.isEmpty())
        assertEquals(ExplosionKind.SHOT, game.explosions.single().kind)
    }

    @ParameterizedTest
    @EnumSource(value = Difficulty::class, names = ["NORMAL", "HARD"])
    fun `enemy fire respects the maximum number of shots`(difficulty: Difficulty) {
        val game = OrbitGuardGame(difficulty, ScriptedRandom(double = 0.0))
        repeat(30) {
            game.step(Input())
            assertTrue(game.enemyShots.size <= difficulty.maxEnemyShots)
        }
        assertEquals(difficulty.maxEnemyShots, game.enemyShots.size)
    }

    @Test
    fun `an aimed shot comes from the lowest enemy of the column nearest the ship`() {
        val game = OrbitGuardGame(Difficulty.NORMAL, ScriptedRandom(double = 0.0))
        game.step(Input())
        assertEquals(listOf(ShotState(159.5, 95.0)), game.enemyShots)
    }

    @Test
    fun `a random shot comes from the lowest alive enemy of a random column`() {
        val game = OrbitGuardGame(Difficulty.NORMAL, ScriptedRandom(double = 0.0) { bound -> if (bound == 3) 1 else 2 })
        val lowest = game.fleet.lowestInColumn(2)!!
        game.fleet.remove(lowest)
        game.step(Input())
        assertEquals(ShotState(105.5, 81.0), game.enemyShots.single())
    }

    @Test
    fun `a player shot and an enemy shot that meet both vanish`() {
        val game = quietGame()
        game.fleet = fleetOf(anchor())
        game.addPlayerShot(100.0, 150.0)
        game.addEnemyShot(100.0, 148.0)
        game.step(Input())
        assertTrue(game.playerShots.isEmpty())
        assertTrue(game.enemyShots.isEmpty())
        assertEquals(ExplosionKind.SHOT, game.explosions.single().kind)
    }

    @Test
    fun `a hit costs a life and freezes the game for 60 steps`() {
        val game = quietGame()
        game.fleet = fleetOf(anchor())
        game.addEnemyShot(10.0, 100.0)
        val events = game.hitShip()
        assertEquals(listOf<GameEvent>(GameEvent.ShipDestroyed), events)
        assertEquals(2, game.lives)
        assertEquals(Phase.SHIP_DOWN, game.phase)
        assertTrue(game.enemyShots.isEmpty())
        assertEquals(ExplosionKind.SHIP, game.explosions.single().kind)
        val before = game.enemies
        game.stepTimes(59, Input(right = true, fire = true))
        assertEquals(Phase.SHIP_DOWN, game.phase)
        assertEquals(before, game.enemies)
        assertEquals(153.5, game.shipX)
        assertTrue(game.playerShots.isEmpty())
        game.step(Input())
        assertEquals(Phase.PLAYING, game.phase)
        assertTrue(game.explosions.isEmpty())
    }

    @Test
    fun `the ship respawns at the centre`() {
        val game = quietGame()
        game.fleet = fleetOf(anchor())
        game.placeShip(100.0)
        game.addEnemyShot(106.0, 212.0)
        game.step(Input())
        game.stepTimes(60)
        assertEquals(Phase.PLAYING, game.phase)
        assertEquals(153.5, game.shipX)
    }

    @Test
    fun `player shots keep flying while the ship is down`() {
        val game = quietGame()
        game.fleet = fleetOf(anchor())
        game.hitShip()
        game.addPlayerShot(200.0, 100.0)
        game.step(Input())
        assertEquals(96.0, game.playerShots.single().y)
    }

    @Test
    fun `the last life ends the game after the explosion with one GameOver`() {
        val game = quietGame()
        game.fleet = fleetOf(anchor())
        val events = mutableListOf<GameEvent>()
        repeat(2) {
            events += game.hitShip()
            events += game.stepTimes(60)
        }
        assertEquals(1, game.lives)
        events += game.hitShip()
        assertEquals(0, game.lives)
        events += game.stepTimes(59)
        assertEquals(Phase.SHIP_DOWN, game.phase)
        events += game.step(Input())
        assertTrue(game.isOver)
        assertEquals(1, events.count { it == GameEvent.GameOver })
        assertEquals(3, events.count { it == GameEvent.ShipDestroyed })
    }

    @Test
    fun `an enemy reaching the ship row ends the game at once`() {
        val game = quietGame()
        game.fleet = fleetOf(enemy(100.0, 208.0))
        val events = game.step(Input())
        assertEquals(listOf<GameEvent>(GameEvent.GameOver), events)
        assertTrue(game.isOver)
        assertEquals(0, game.lives)
    }

    @Test
    fun `an enemy one pixel above the ship row does not end the game`() {
        val game = quietGame()
        game.fleet = fleetOf(enemy(100.0, 207.0))
        assertTrue(game.step(Input()).isEmpty())
        assertEquals(Phase.PLAYING, game.phase)
    }

    @Test
    fun `step does nothing when the game is over`() {
        val game = quietGame()
        game.fleet = fleetOf(enemy(100.0, 208.0))
        game.step(Input())
        val enemies = game.enemies
        val explosions = game.explosions
        assertTrue(game.step(Input(right = true, fire = true)).isEmpty())
        assertEquals(153.5, game.shipX)
        assertTrue(game.playerShots.isEmpty())
        assertEquals(enemies, game.enemies)
        assertEquals(explosions, game.explosions)
    }

    @Test
    fun `an extra life comes once at 1500 points`() {
        val game = quietGame()
        game.addScore(1499)
        assertTrue(game.step(Input()).isEmpty())
        game.addScore(1)
        assertEquals(listOf<GameEvent>(GameEvent.ExtraLife), game.step(Input()))
        assertEquals(4, game.lives)
        game.addScore(1500)
        assertTrue(game.step(Input()).isEmpty())
        assertEquals(4, game.lives)
    }

    @Test
    fun `the bonus ship appears only with at least 8 enemies alive`() {
        val few = quietGame()
        few.fleet = Fleet(Fleet.create(1, 1.0).enemies.take(7), 1.0)
        few.setBonusCountdown(1)
        few.step(Input())
        assertNull(few.bonus)

        val enough = quietGame()
        enough.fleet = Fleet(enough.fleet.enemies.take(8), 1.0)
        enough.setBonusCountdown(1)
        enough.step(Input())
        assertEquals(BonusState(-16.0, 16.0), enough.bonus)
    }

    @Test
    fun `the bonus ship crosses the field and leaves`() {
        val game = quietGame()
        game.setBonusCountdown(1)
        game.step(Input())
        game.stepTimes(300)
        assertNotNull(game.bonus)
        game.stepTimes(40)
        assertNull(game.bonus)
    }

    @Test
    fun `a destroyed bonus ship pays one of the four values`() {
        val paid =
            (0L..40L)
                .map { seed ->
                    val game = OrbitGuardGame(Difficulty.NORMAL, Random(seed))
                    game.setBonusCountdown(1)
                    game.step(Input())
                    val bonus = game.bonus!!
                    game.addPlayerShot(bonus.x + 2, 26.0)
                    val event = game.step(Input()).filterIsInstance<GameEvent.BonusDestroyed>().single()
                    assertEquals(event.points, game.score)
                    assertEquals(event.points, game.explosions.single { it.kind == ExplosionKind.BONUS }.points)
                    assertNull(game.bonus)
                    event.points
                }.toSet()
        assertEquals(setOf(50, 100, 150, 300), paid)
    }

    private fun OrbitGuardGame.clearWaveWithOneShot(): List<GameEvent> {
        val last = enemy(100.0, 100.0, column = 5)
        fleet = fleetOf(last)
        addPlayerShot(last.x + 2, last.y + 10)
        return step(Input())
    }

    @Test
    fun `clearing a wave starts a 90 step break then the next wave with fresh shields`() {
        val game = quietGame()
        game.shields.forEach { it.clearChunk(it.x + 12, it.y + 8, ScriptedRandom(0.0)) }
        game.addEnemyShot(10.0, 100.0)
        val events = game.clearWaveWithOneShot()
        assertEquals(GameEvent.WaveCleared(1), events.last())
        assertEquals(Phase.WAVE_BREAK, game.phase)
        assertTrue(game.enemyShots.isEmpty())
        game.stepTimes(89)
        assertEquals(Phase.WAVE_BREAK, game.phase)
        assertEquals(1, game.wave)
        game.step(Input())
        assertEquals(Phase.PLAYING, game.phase)
        assertEquals(2, game.wave)
        assertEquals(55, game.enemies.size)
        assertTrue(game.shields.all { it.solidCount() == Shield(0, 0).solidCount() })
    }

    @Test
    fun `the fleet starts 8 px lower per wave capped from wave 5`() {
        val game = quietGame()
        val tops = mutableListOf<Double>()
        repeat(6) {
            game.clearWaveWithOneShot()
            game.stepTimes(90)
            tops += game.enemies.minOf { it.y }
        }
        assertEquals(listOf(40.0, 48.0, 56.0, 64.0, 64.0, 64.0), tops)
    }

    @Test
    fun `the bonus ship is removed when a wave is cleared`() {
        val game = quietGame()
        game.setBonusCountdown(1)
        game.step(Input())
        assertNotNull(game.bonus)
        game.clearWaveWithOneShot()
        assertNull(game.bonus)
    }

    @ParameterizedTest
    @EnumSource(Difficulty::class)
    fun `the difficulty sets lives and fleet speed`(difficulty: Difficulty) {
        val game = quietGame(difficulty)
        assertEquals(difficulty.lives, game.lives)
        assertEquals(Fleet.intervalFor(55, difficulty.fleetSpeed), game.fleet.interval())
        assertFalse(game.isOver)
    }

    @Test
    fun `an enemy overlapping a shield erodes it`() {
        val game = quietGame()
        val shield = game.shields[0]
        val before = shield.solidCount()
        game.fleet = fleetOf(enemy(shield.x + 6.0, shield.y + 4.0, EnemyKind.BRUTE))
        game.step(Input())
        assertTrue(shield.solidCount() < before)
    }

    @Test
    fun `a shot erodes a shield and ends`() {
        val game = quietGame()
        game.fleet = fleetOf(anchor())
        val shield = game.shields[0]
        val before = shield.solidCount()
        game.addPlayerShot(shield.x + 12.0, 202.0)
        game.step(Input())
        assertTrue(game.playerShots.isEmpty())
        assertTrue(shield.solidCount() < before)
        assertEquals(ExplosionKind.SHOT, game.explosions.single().kind)
    }

    @Test
    fun `an enemy shot ends at the ground line`() {
        val game = quietGame()
        game.fleet = fleetOf(anchor())
        game.addEnemyShot(10.0, 220.0)
        game.step(Input())
        assertEquals(222.0, game.enemyShots.single().y)
        game.step(Input())
        assertTrue(game.enemyShots.isEmpty())
        assertEquals(ExplosionKind.SHOT, game.explosions.single().kind)
    }

    @ParameterizedTest
    @CsvSource(
        "EASY,1,0.012",
        "NORMAL,1,0.02",
        "HARD,1,0.03",
        "EASY,6,0.018",
        "NORMAL,6,0.03",
        "HARD,6,0.045",
        "NORMAL,8,0.03",
        "HARD,9,0.045",
    )
    fun `the enemy fire chance scales with fire rate and wave up to plus 50 percent`(
        difficulty: Difficulty,
        wave: Int,
        chance: Double,
    ) {
        val random = ScriptedRandom()
        val game = OrbitGuardGame(difficulty, random)
        repeat(wave - 1) {
            game.clearWaveWithOneShot()
            game.stepTimes(90)
        }
        assertEquals(wave, game.wave)
        random.double = chance + 1e-9
        game.step(Input())
        assertTrue(game.enemyShots.isEmpty(), "above the chance")
        random.double = chance - 1e-9
        game.step(Input())
        assertEquals(1, game.enemyShots.size, "below the chance")
    }

    @ParameterizedTest
    @CsvSource("178.0,false", "179.0,true")
    fun `an enemy shot hits a shield when its leading row, 3 below its top, reaches it`(
        top: Double,
        hits: Boolean,
    ) {
        val game = quietGame()
        game.fleet = fleetOf(anchor())
        val shield = game.shields[0]
        game.addEnemyShot(shield.x + 12.0, top)
        game.step(Input())
        assertEquals(hits, game.enemyShots.isEmpty())
        assertEquals(!hits, shield.isSolid(12, 0))
        assertEquals(if (hits) 1 else 0, game.explosions.count { it.kind == ExplosionKind.SHOT })
    }

    @Test
    fun `a new enemy shot that starts inside a shield hits it on its first move`() {
        val random = ScriptedRandom(double = 0.0)
        val game = OrbitGuardGame(Difficulty.NORMAL, random)
        val shield = game.shields[0]
        game.fleet = fleetOf(enemy(shield.x + 8.0, 189.0))
        game.step(Input())
        val first = game.enemyShots.single()
        assertEquals(197.0, first.y)
        game.step(Input())
        assertFalse(shield.isSolid(14, 13))
        assertTrue(game.enemyShots.none { it.y >= 199.0 })
        assertEquals(1, game.explosions.count { it.kind == ExplosionKind.SHOT })
    }

    @Test
    fun `a player shot hits the nearer of an enemy and a shield on its path`() {
        val shieldAbove = quietGame()
        val shield = shieldAbove.shields[0]
        val high = enemy(shield.x + 8.0, 191.0)
        shieldAbove.fleet = fleetOf(high, anchor())
        shieldAbove.addPlayerShot(shield.x + 12.0, 202.0)
        shieldAbove.step(Input())
        assertEquals(2, shieldAbove.enemies.size)
        assertTrue(shieldAbove.explosions.any { it.kind == ExplosionKind.SHOT })
        assertTrue(shieldAbove.explosions.none { it.kind == ExplosionKind.ENEMY })

        val enemyAbove = quietGame()
        val intact = enemyAbove.shields[0]
        enemyAbove.fleet = fleetOf(enemy(intact.x + 8.0, 194.0), anchor())
        enemyAbove.addPlayerShot(intact.x + 12.0, 202.0)
        enemyAbove.step(Input())
        assertEquals(1, enemyAbove.enemies.size)
        assertTrue(enemyAbove.explosions.any { it.kind == ExplosionKind.ENEMY })
    }

    @Test
    fun `two shots reaching one enemy in the same step destroy it once`() {
        val game = quietGame()
        val target = enemy(100.0, 100.0, column = 5)
        game.fleet = fleetOf(anchor(), target)
        game.addPlayerShot(102.0, 110.0)
        game.addPlayerShot(104.0, 108.0)
        val events = game.step(Input())
        assertEquals(1, events.count { it is GameEvent.EnemyDestroyed })
        assertEquals(1, game.playerShots.size)
        assertEquals(30, game.score)
    }
}
