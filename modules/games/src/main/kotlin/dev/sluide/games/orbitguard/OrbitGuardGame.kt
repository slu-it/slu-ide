package dev.sluide.games.orbitguard

import dev.sluide.games.orbitguard.Rules.overlaps
import kotlin.math.floor
import kotlin.random.Random

/**
 * The rules of Orbit Guard, a fixed shooter: no JavaFX, not thread-safe, deterministic for a seeded [random].
 * [step] advances one 1/60 s. Only nextDouble and nextInt(bound) of the random are used.
 *
 * Phase choices: the ship moves in PLAYING and WAVE_BREAK, fires only in PLAYING; a cleared wave is detected only in
 * PLAYING (the last enemy dying in SHIP_DOWN clears the wave once play resumes); an extra life earned during the
 * SHIP_DOWN of the last life saves the game. An enemy overlapping a shield clears the pixels under its set sprite
 * pixels. An enemy shot ends at the ground line when its bottom reaches y = 228.
 *
 * `fleet`, `placeShip`, `addScore`, `addPlayerShot`, `addEnemyShot` and `setBonusCountdown` are test seams only.
 */
internal class OrbitGuardGame(
    val difficulty: Difficulty,
    private val random: Random,
) {
    private class Shot(
        var x: Double,
        var y: Double,
    )

    var score = 0
        private set
    var lives = difficulty.lives
        private set
    var wave = 1
        private set
    var phase = Phase.PLAYING
        private set
    val isOver: Boolean get() = phase == Phase.OVER

    var shipX = SHIP_START_X
        private set
    val shipY: Double = Rules.SHIP_TOP.toDouble()

    internal var fleet: Fleet = Fleet.create(1, difficulty.fleetSpeed)
    val enemies: List<EnemyState> get() = fleet.enemies.map { EnemyState(it.kind, it.x, it.y, it.column, it.row) }
    val animationFrame: Int get() = fleet.frame

    private val playerShotList = mutableListOf<Shot>()
    private val enemyShotList = mutableListOf<Shot>()
    val playerShots: List<ShotState> get() = playerShotList.map { ShotState(it.x, it.y) }
    val enemyShots: List<ShotState> get() = enemyShotList.map { ShotState(it.x, it.y) }

    private val bonuses = BonusTracker(random)
    val bonus: BonusState? get() = bonuses.ship?.let { BonusState(it.x, Rules.BONUS_Y) }

    var shields: List<Shield> = newShields()
        private set

    var explosions: List<ExplosionState> = emptyList()
        private set

    private var cooldown = 0
    private var phaseTimer = 0
    private var extraLifeGiven = false

    /** Test seams. [addScore] is checked for the extra life in the next step. */
    internal fun placeShip(x: Double) = run { shipX = x }

    internal fun addScore(points: Int) = run { score += points }

    internal fun addPlayerShot(
        x: Double,
        y: Double,
    ) = playerShotList.add(Shot(x, y))

    internal fun addEnemyShot(
        x: Double,
        y: Double,
    ) = enemyShotList.add(Shot(x, y))

    internal fun setBonusCountdown(steps: Int) = run { bonuses.countdown = steps }

    fun step(input: Input): List<GameEvent> {
        if (phase == Phase.OVER) return emptyList()
        val events = mutableListOf<GameEvent>()
        val started = phase
        ageExplosions()
        if (started == Phase.PLAYING || started == Phase.WAVE_BREAK) moveShip(input)
        if (cooldown > 0) cooldown--
        movePlayerShots(events)
        if (started == Phase.PLAYING) {
            if (input.fire) tryFire()
            fleet.tick()
            clearShieldsUnderEnemies()
            if (fleet.enemies.any { it.y + it.height >= Rules.SHIP_TOP }) {
                invade(events)
                return events
            }
            bonuses.tick(fleet.alive)
            if (phase == Phase.PLAYING) {
                moveEnemyShots(events)
                enemyFire()
            }
        }
        if (!extraLifeGiven && score >= EXTRA_LIFE_SCORE) {
            extraLifeGiven = true
            lives++
            events.add(GameEvent.ExtraLife)
        }
        when (started) {
            Phase.PLAYING -> if (phase == Phase.PLAYING && fleet.enemies.isEmpty()) clearWave(events)
            Phase.SHIP_DOWN -> if (--phaseTimer == 0) endShipDown(events)
            Phase.WAVE_BREAK -> if (--phaseTimer == 0) startNextWave()
            Phase.OVER -> Unit
        }
        return events
    }

    private fun newShields() = Rules.SHIELD_CENTERS.map { Shield(it - Sprites.SHIELD.width / 2, Rules.SHIELD_Y) }

    private fun explode(
        kind: ExplosionKind,
        x: Double,
        y: Double,
        points: Int = 0,
    ) {
        explosions = explosions + ExplosionState(kind, x, y, 0, points)
    }

    private fun ageExplosions() {
        explosions = explosions.map { it.copy(age = it.age + 1) }.filter { it.age < it.kind.duration }
    }

    private fun shotExplosion(
        shot: Shot,
        y: Double,
    ) = explode(
        ExplosionKind.SHOT,
        shot.x + 0.5 - Sprites.SHOT_EXPLOSION.width / 2.0,
        y - Sprites.SHOT_EXPLOSION.height / 2.0,
    )

    private fun moveShip(input: Input) {
        val dir = (if (input.right) 1 else 0) - (if (input.left) 1 else 0)
        val max = (WIDTH - Rules.SHIP_MARGIN - Rules.SHIP_WIDTH).toDouble()
        shipX = (shipX + dir * Rules.SHIP_SPEED).coerceIn(Rules.SHIP_MARGIN.toDouble(), max)
    }

    private fun tryFire() {
        if (cooldown > 0 || playerShotList.size >= Rules.MAX_PLAYER_SHOTS) return
        playerShotList.add(
            Shot(
                shipX + (Rules.SHIP_WIDTH - Rules.SHOT_WIDTH) / 2,
                Rules.SHIP_TOP - Rules.SHOT_HEIGHT.toDouble(),
            ),
        )
        cooldown = Rules.FIRE_COOLDOWN
    }

    /** Each shot takes the nearest thing on its swept path (largest bottom edge, as it moves up). */
    private fun movePlayerShots(events: MutableList<GameEvent>) {
        for (shot in playerShotList.toList()) {
            if (shot !in playerShotList) continue
            val oldTop = shot.y
            val newTop = oldTop - Rules.PLAYER_SHOT_SPEED
            val sweptHeight = (oldTop - newTop).toInt() + Rules.SHOT_HEIGHT
            val nearest = Nearest(largerWins = true)
            for (e in fleet.enemies) {
                if (sweptHits(shot.x, newTop, sweptHeight, e.x, e.y, e.width, e.height)) {
                    nearest.consider(e.y + e.height) { killEnemy(e, shot, events) }
                }
            }
            bonuses.ship?.let { b ->
                val bonusHeight = Sprites.BONUS.height
                if (sweptHits(shot.x, newTop, sweptHeight, b.x, Rules.BONUS_Y, Sprites.BONUS.width, bonusHeight)) {
                    nearest.consider(Rules.BONUS_Y + bonusHeight) { killBonus(b, shot, events) }
                }
            }
            for (es in enemyShotList) {
                if (sweptHits(shot.x, newTop, sweptHeight, es.x, es.y, Rules.SHOT_WIDTH, Rules.SHOT_HEIGHT)) {
                    nearest.consider(es.y + Rules.SHOT_HEIGHT) {
                        playerShotList.remove(shot)
                        enemyShotList.remove(es)
                        shotExplosion(shot, es.y + Rules.SHOT_HEIGHT)
                    }
                }
            }
            val column = floor(shot.x).toInt()
            for (sh in shields) {
                val row = sh.firstSolid(column, floor(oldTop).toInt(), floor(newTop).toInt()) ?: continue
                nearest.consider(row + 1.0) { hitShield(sh, shot, column, row) }
            }
            if (nearest.run()) continue
            if (newTop < Rules.HUD_HEIGHT) {
                playerShotList.remove(shot)
                shotExplosion(shot, Rules.HUD_HEIGHT.toDouble() + Sprites.SHOT_EXPLOSION.height / 2.0)
            } else {
                shot.y = newTop
            }
        }
    }

    private fun hitShield(
        shield: Shield,
        shot: Shot,
        column: Int,
        row: Int,
    ) {
        shield.clearChunk(column, row, random)
        playerShotList.remove(shot)
        enemyShotList.remove(shot)
        shotExplosion(shot, row.toDouble())
    }

    private fun killEnemy(
        enemy: FleetEnemy,
        shot: Shot,
        events: MutableList<GameEvent>,
    ) {
        playerShotList.remove(shot)
        fleet.remove(enemy)
        score += enemy.kind.points
        events.add(GameEvent.EnemyDestroyed(enemy.kind, enemy.kind.points))
        explode(ExplosionKind.ENEMY, enemy.x + enemy.width / 2.0 - Sprites.ENEMY_EXPLOSION.width / 2.0, enemy.y)
    }

    private fun killBonus(
        ship: BonusShip,
        shot: Shot,
        events: MutableList<GameEvent>,
    ) {
        playerShotList.remove(shot)
        bonuses.remove()
        score += ship.points
        events.add(GameEvent.BonusDestroyed(ship.points))
        explode(
            ExplosionKind.BONUS,
            ship.x + Sprites.BONUS.width / 2.0 - Sprites.ENEMY_EXPLOSION.width / 2.0,
            Rules.BONUS_Y,
            ship.points,
        )
    }

    private fun clearShieldsUnderEnemies() {
        for (e in fleet.enemies) {
            for (sh in shields) {
                if (overlaps(e.x, e.y, e.width, e.height, sh.x.toDouble(), sh.y.toDouble(), sh.width, sh.height)) {
                    sh.clearOverlap(Sprites.enemy(e.kind, fleet.frame), floor(e.x).toInt(), floor(e.y).toInt())
                }
            }
        }
    }

    private fun invade(events: MutableList<GameEvent>) {
        lives = 0
        phase = Phase.OVER
        events.add(GameEvent.GameOver)
    }

    /** Enemy shots move down; the nearest thing on the swept path (smallest leading row) ends the shot. */
    private fun moveEnemyShots(events: MutableList<GameEvent>) {
        for (shot in enemyShotList.toList()) {
            if (shot !in enemyShotList) continue
            val oldTop = shot.y
            val newTop = oldTop + Rules.ENEMY_SHOT_SPEED
            val sweptHeight = Rules.ENEMY_SHOT_SPEED + Rules.SHOT_HEIGHT
            val nearest = Nearest(largerWins = false)
            if (sweptHits(shot.x, oldTop, sweptHeight, shipX, shipY, Rules.SHIP_WIDTH, Rules.SHIP_HEIGHT)) {
                nearest.consider(shipY) { hitShip(events) }
            }
            for (ps in playerShotList) {
                if (sweptHits(shot.x, oldTop, sweptHeight, ps.x, ps.y, Rules.SHOT_WIDTH, Rules.SHOT_HEIGHT)) {
                    nearest.consider(ps.y) {
                        playerShotList.remove(ps)
                        enemyShotList.remove(shot)
                        shotExplosion(shot, ps.y)
                    }
                }
            }
            val column = floor(shot.x).toInt()
            for (sh in shields) {
                // from the shot's top (a fresh shot may start inside a shield) to its leading row
                val row =
                    sh.firstSolid(column, floor(oldTop).toInt(), floor(newTop).toInt() + Rules.SHOT_HEIGHT - 1)
                        ?: continue
                nearest.consider(row.toDouble()) { hitShield(sh, shot, column, row) }
            }
            if (!nearest.run()) {
                if (newTop + Rules.SHOT_HEIGHT >= Rules.GROUND_Y) {
                    enemyShotList.remove(shot)
                    shotExplosion(shot, Rules.GROUND_Y.toDouble())
                } else {
                    shot.y = newTop
                }
            }
            if (phase != Phase.PLAYING) return
        }
    }

    private fun hitShip(events: MutableList<GameEvent>) {
        lives--
        phase = Phase.SHIP_DOWN
        phaseTimer = Rules.SHIP_DOWN_STEPS
        enemyShotList.clear()
        events.add(GameEvent.ShipDestroyed)
        explode(ExplosionKind.SHIP, shipX + Rules.SHIP_WIDTH / 2.0 - Sprites.SHIP_EXPLOSION.width / 2.0, shipY)
    }

    private fun endShipDown(events: MutableList<GameEvent>) {
        if (lives > 0) {
            shipX = SHIP_START_X
            phase = Phase.PLAYING
        } else {
            phase = Phase.OVER
            events.add(GameEvent.GameOver)
        }
    }

    private fun enemyFire() {
        if (enemyShotList.size >= difficulty.maxEnemyShots || fleet.enemies.isEmpty()) return
        val waveBonus = 1 + 0.1 * minOf(wave - 1, Rules.MAX_FIRE_BONUS_WAVES)
        if (random.nextDouble() >= Rules.BASE_FIRE_CHANCE * difficulty.fireRate * waveBonus) return
        val shooter =
            if (random.nextInt(Rules.AIMED_FIRE_ONE_IN) == 0) {
                fleet.lowestNear(shipX + Rules.SHIP_WIDTH / 2.0)
            } else {
                val columns = fleet.columns()
                fleet.lowestInColumn(columns[random.nextInt(columns.size)])
            } ?: return
        enemyShotList.add(Shot(shooter.x + (shooter.width - Rules.SHOT_WIDTH) / 2.0, shooter.y + shooter.height))
    }

    private fun clearWave(events: MutableList<GameEvent>) {
        events.add(GameEvent.WaveCleared(wave))
        phase = Phase.WAVE_BREAK
        phaseTimer = Rules.WAVE_BREAK_STEPS
        playerShotList.clear()
        enemyShotList.clear()
        bonuses.remove()
    }

    private fun startNextWave() {
        wave++
        fleet = Fleet.create(wave, difficulty.fleetSpeed)
        shields = newShields()
        phase = Phase.PLAYING
    }

    companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
        const val STEPS_PER_SECOND = 60
        const val EXTRA_LIFE_SCORE = 1500
        private const val SHIP_START_X = (WIDTH - Rules.SHIP_WIDTH) / 2.0
    }
}
