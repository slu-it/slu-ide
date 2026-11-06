package dev.sluide.games.doubles

/** A board of [side] by [side] cells; a tile of value [goal] wins the game (play goes on). */
internal enum class BoardSize(
    val side: Int,
    val goal: Int,
    val label: String,
) {
    THREE(3, 512, "3×3"),
    FOUR(4, 2048, "4×4"),
    FIVE(5, 4096, "5×5"),
    SIX(6, 8192, "6×6"),
    ;

    companion object {
        val DEFAULT = FOUR

        fun ofSide(side: Int): BoardSize? = entries.firstOrNull { it.side == side }
    }
}
