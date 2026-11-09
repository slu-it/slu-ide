package dev.sluide.games.pipepuzzle

import kotlin.random.Random

/** Makes a [Puzzle] for a mode and size; blocking, never call it on the FX thread. */
fun interface PuzzleGenerator {
    fun generate(
        mode: Mode,
        size: BoardSize,
        random: Random,
    ): Puzzle
}

/** Picks the generator of the mode; the same [Random] seed gives the same puzzle. */
class Generator : PuzzleGenerator {
    override fun generate(
        mode: Mode,
        size: BoardSize,
        random: Random,
    ): Puzzle =
        when (mode) {
            Mode.NETWORK -> NetworkGenerator().generate(size.side, random)
            Mode.PATH -> PathGenerator().generate(size.side, random)
        }
}
