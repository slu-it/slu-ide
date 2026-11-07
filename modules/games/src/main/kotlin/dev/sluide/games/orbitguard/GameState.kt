package dev.sluide.games.orbitguard

internal enum class Phase { PLAYING, SHIP_DOWN, WAVE_BREAK, OVER }

/** Read-only snapshots for the renderer; x and y are the left/top of the sprite in field pixels. */
internal data class EnemyState(
    val kind: EnemyKind,
    val x: Double,
    val y: Double,
    val column: Int,
    val row: Int,
)

internal data class ShotState(
    val x: Double,
    val y: Double,
)

internal data class BonusState(
    val x: Double,
    val y: Double,
)

internal enum class ExplosionKind(
    val duration: Int,
) {
    ENEMY(16),
    SHIP(60),
    BONUS(30),
    SHOT(8),
}

/** [age] counts steps since the explosion began; [points] is shown for a destroyed bonus ship. */
internal data class ExplosionState(
    val kind: ExplosionKind,
    val x: Double,
    val y: Double,
    val age: Int,
    val points: Int = 0,
)

/** The numbers of the rules, in field pixels and steps. */
internal object Rules {
    const val HUD_HEIGHT = 12
    const val GROUND_Y = 228
    const val SHIP_TOP = 216
    const val SHIP_WIDTH = 13
    const val SHIP_HEIGHT = 8
    const val SHIP_MARGIN = 4
    const val SHIP_SPEED = 1.5
    const val SHOT_WIDTH = 1
    const val SHOT_HEIGHT = 4
    const val PLAYER_SHOT_SPEED = 4
    const val ENEMY_SHOT_SPEED = 2
    const val MAX_PLAYER_SHOTS = 3
    const val FIRE_COOLDOWN = 15
    const val SHIP_DOWN_STEPS = 60
    const val WAVE_BREAK_STEPS = 90
    const val BASE_FIRE_CHANCE = 0.02
    const val AIMED_FIRE_ONE_IN = 3
    const val MAX_FIRE_BONUS_WAVES = 5
    const val BONUS_Y = 16.0
    const val BONUS_MIN_ENEMIES = 8
    const val BONUS_MIN_COUNTDOWN = 1200
    const val BONUS_COUNTDOWN_SPREAD = 601
    val BONUS_POINTS = listOf(50, 100, 150, 300)
    const val SHIELD_Y = 184
    val SHIELD_CENTERS = listOf(64, 128, 192, 256)

    fun overlaps(
        ax: Double,
        ay: Double,
        aw: Int,
        ah: Int,
        bx: Double,
        by: Double,
        bw: Int,
        bh: Int,
    ): Boolean = ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah
}
