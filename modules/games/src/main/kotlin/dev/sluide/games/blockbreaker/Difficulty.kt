package dev.sluide.games.blockbreaker

/** Block Breaker's difficulty: [ballSpeed] in px/s, [paddleWidth] in logical px, [capsuleChance] per destroyed brick. */
internal enum class Difficulty(
    val id: String,
    val label: String,
    val lives: Int,
    val ballSpeed: Double,
    val paddleWidth: Int,
    val capsuleChance: Double,
) {
    EASY("easy", "Easy", 5, 120.0, 48, 0.15),
    NORMAL("normal", "Normal", 3, 150.0, 40, 0.12),
    HARD("hard", "Hard", 3, 180.0, 32, 0.10),
    ;

    companion object {
        val DEFAULT = NORMAL

        fun ofId(id: String?): Difficulty? = entries.firstOrNull { it.id == id }
    }
}
