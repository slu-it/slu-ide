package dev.sluide.games.orbitguard

/** Orbit Guard's difficulty: [fleetSpeed] divides the march interval, [fireRate] multiplies the enemy fire chance. */
internal enum class Difficulty(
    val id: String,
    val label: String,
    val lives: Int,
    val fleetSpeed: Double,
    val fireRate: Double,
    val maxEnemyShots: Int,
) {
    EASY("easy", "Easy", 4, 0.8, 0.6, 3),
    NORMAL("normal", "Normal", 3, 1.0, 1.0, 3),
    HARD("hard", "Hard", 3, 1.25, 1.5, 4),
    ;

    companion object {
        val DEFAULT = NORMAL

        fun ofId(id: String?): Difficulty? = entries.firstOrNull { it.id == id }
    }
}
