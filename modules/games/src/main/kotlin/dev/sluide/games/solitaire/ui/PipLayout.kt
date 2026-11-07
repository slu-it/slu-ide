package dev.sluide.games.solitaire.ui

/** The centre of one pip in unit coordinates of the pip area (0..1 by 0..1); [inverted] pips are turned upside down. */
internal data class Pip(
    val x: Double,
    val y: Double,
    val inverted: Boolean,
)

/** The classic pip arrangements of the Ace to the Ten. */
internal object PipLayout {
    private const val LEFT = 0.0
    private const val MID = 0.5
    private const val RIGHT = 1.0

    private val rows =
        mapOf(
            1 to listOf(MID to 0.5),
            2 to listOf(MID to 0.0, MID to 1.0),
            3 to listOf(MID to 0.0, MID to 0.5, MID to 1.0),
            4 to corners(),
            5 to corners() + (MID to 0.5),
            6 to sides(0.0, 0.5, 1.0),
            7 to sides(0.0, 0.5, 1.0) + (MID to 0.25),
            8 to sides(0.0, 0.5, 1.0) + listOf(MID to 0.25, MID to 0.75),
            9 to sides(0.0, 1.0 / 3, 2.0 / 3, 1.0) + (MID to 0.5),
            10 to sides(0.0, 1.0 / 3, 2.0 / 3, 1.0) + listOf(MID to 1.0 / 6, MID to 5.0 / 6),
        )

    private fun corners() = sides(0.0, 1.0)

    private fun sides(vararg ys: Double) = ys.flatMap { listOf(LEFT to it, RIGHT to it) }

    /** The pips of [rank] 1..10; empty for the faces, which show an emblem instead. */
    fun pips(rank: Int): List<Pip> = rows[rank].orEmpty().map { (x, y) -> Pip(x, y, inverted = y > 0.5) }
}
