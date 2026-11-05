package dev.sluide.games.minesweeper

/** A board of [width] columns and [height] rows with [mines] mines; the limits are those of [Companion]. */
data class BoardSize(
    val width: Int,
    val height: Int,
    val mines: Int,
) {
    init {
        require(width in MIN_WIDTH..MAX_WIDTH) { "width $width not in $MIN_WIDTH..$MAX_WIDTH" }
        require(height in MIN_HEIGHT..MAX_HEIGHT) { "height $height not in $MIN_HEIGHT..$MAX_HEIGHT" }
        require(mines in 1..maxMines(width, height)) { "mines $mines not in 1..${maxMines(width, height)}" }
    }

    companion object {
        const val MIN_WIDTH = 5
        const val MAX_WIDTH = 50
        const val MIN_HEIGHT = 5
        const val MAX_HEIGHT = 30

        /** The first opening's 3×3 area stays free of mines. */
        fun maxMines(
            width: Int,
            height: Int,
        ): Int = width * height - 9

        /** The size with each value moved into its range (the mines into the range of the clamped width and height). */
        fun clamped(
            width: Int,
            height: Int,
            mines: Int,
        ): BoardSize {
            val w = width.coerceIn(MIN_WIDTH, MAX_WIDTH)
            val h = height.coerceIn(MIN_HEIGHT, MAX_HEIGHT)
            return BoardSize(w, h, mines.coerceIn(1, maxMines(w, h)))
        }
    }
}

/** The classic sizes. */
enum class Preset(
    val label: String,
    val size: BoardSize,
) {
    BEGINNER("Beginner", BoardSize(9, 9, 10)),
    INTERMEDIATE("Intermediate", BoardSize(16, 16, 40)),
    EXPERT("Expert", BoardSize(30, 16, 99)),
}

/** A [Preset] or a custom [BoardSize]. */
sealed interface SizeChoice {
    val size: BoardSize

    data class OfPreset(
        val preset: Preset,
    ) : SizeChoice {
        override val size: BoardSize get() = preset.size
    }

    data class Custom(
        override val size: BoardSize,
    ) : SizeChoice
}
