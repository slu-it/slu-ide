package dev.sluide.games.orbitguard

import kotlin.math.abs
import kotlin.math.roundToInt

/** An enemy of the fleet; [x] and [y] are the sprite's left/top. */
internal class FleetEnemy(
    val kind: EnemyKind,
    val column: Int,
    val row: Int,
    var x: Double,
    var y: Double,
) {
    val width: Int get() = Sprites.enemy(kind, 0).width
    val height: Int get() = Sprites.enemy(kind, 0).height
}

/** The marching fleet: [tick] is called once per playing step and moves it every [interval] steps. */
internal class Fleet(
    enemies: List<FleetEnemy>,
    private val fleetSpeed: Double,
) {
    val enemies: MutableList<FleetEnemy> = enemies.toMutableList()

    /** 1 to the right, -1 to the left. */
    var direction = 1
        private set

    /** 0 or 1, toggles with every march step. */
    var frame = 0
        private set

    private var counter = 0

    val alive: Int get() = enemies.size

    fun interval(): Int = intervalFor(alive, fleetSpeed)

    /** Returns true when the fleet marched (moved or dropped) in this step. */
    fun tick(): Boolean {
        if (enemies.isEmpty()) return false
        counter++
        if (counter < interval()) return false
        counter = 0
        val dx = (MARCH_PX * direction).toDouble()
        if (enemies.any { it.x + dx < LEFT_LIMIT || it.x + dx + it.width > RIGHT_LIMIT }) {
            enemies.forEach { it.y += DROP_PX }
            direction = -direction
        } else {
            enemies.forEach { it.x += dx }
        }
        frame = 1 - frame
        return true
    }

    fun lowestInColumn(column: Int): FleetEnemy? = enemies.filter { it.column == column }.maxByOrNull { it.row }

    /** The lowest enemy of the column whose lowest enemy is horizontally closest to [targetX]. */
    fun lowestNear(targetX: Double): FleetEnemy? =
        columns().mapNotNull { lowestInColumn(it) }.minByOrNull { abs(it.x + it.width / 2.0 - targetX) }

    fun columns(): List<Int> = enemies.map { it.column }.distinct().sorted()

    fun remove(enemy: FleetEnemy) {
        enemies.remove(enemy)
    }

    companion object {
        const val ROWS = 5
        const val COLUMNS = 11
        const val CELL_WIDTH = 18
        const val CELL_HEIGHT = 14
        const val MARCH_PX = 2
        const val DROP_PX = 8
        const val LEFT_LIMIT = 4.0
        const val RIGHT_LIMIT = 316.0
        const val FIRST_ROW_Y = 32
        const val MAX_LOWERED_WAVES = 4
        const val LOWER_PER_WAVE = 8
        private const val SLOWEST_INTERVAL = 49.0
        private const val ALL_BUT_ONE = 54.0

        fun intervalFor(
            alive: Int,
            fleetSpeed: Double,
        ): Int = maxOf(1, ((1 + SLOWEST_INTERVAL * (alive - 1) / ALL_BUT_ONE) / fleetSpeed).roundToInt())

        fun topY(wave: Int): Int = FIRST_ROW_Y + LOWER_PER_WAVE * minOf(wave - 1, MAX_LOWERED_WAVES)

        fun kindOfRow(row: Int): EnemyKind =
            when (row) {
                0 -> EnemyKind.SCOUT
                1, 2 -> EnemyKind.RAIDER
                else -> EnemyKind.BRUTE
            }

        fun create(
            wave: Int,
            fleetSpeed: Double,
        ): Fleet {
            val left = (OrbitGuardGame.WIDTH - COLUMNS * CELL_WIDTH) / 2
            val top = topY(wave)
            val list =
                (0 until ROWS).flatMap { row ->
                    val kind = kindOfRow(row)
                    val width = Sprites.enemy(kind, 0).width
                    (0 until COLUMNS).map { col ->
                        FleetEnemy(
                            kind,
                            col,
                            row,
                            (left + col * CELL_WIDTH + (CELL_WIDTH - width) / 2).toDouble(),
                            (top + row * CELL_HEIGHT).toDouble(),
                        )
                    }
                }
            return Fleet(list, fleetSpeed)
        }
    }
}
