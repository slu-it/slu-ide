package dev.sluide.games.blockbreaker

import kotlin.random.Random

/** Returns the given doubles in turn (then again from the start) and always the same boolean. */
internal class FixedRandom(
    private val doubles: List<Double> = listOf(0.999),
    private val bool: Boolean = false,
) : Random() {
    private var next = 0

    override fun nextBits(bitCount: Int) = 0

    override fun nextDouble() = doubles[next++ % doubles.size]

    override fun nextBoolean() = bool
}
