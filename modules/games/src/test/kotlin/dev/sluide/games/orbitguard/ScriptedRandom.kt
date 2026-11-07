package dev.sluide.games.orbitguard

import kotlin.random.Random

/** A random whose answers the test dictates: [double] for every nextDouble, [int] for every bounded nextInt. */
internal class ScriptedRandom(
    var double: Double = 1.0,
    private val int: (Int) -> Int = { 0 },
) : Random() {
    override fun nextBits(bitCount: Int) = 0

    override fun nextDouble() = double

    override fun nextInt(until: Int) = int(until)
}
