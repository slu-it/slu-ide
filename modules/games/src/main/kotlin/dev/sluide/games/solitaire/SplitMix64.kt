package dev.sluide.games.solitaire

/**
 * The SplitMix64 generator (Steele, Lea, Flood 2014), written here instead of `java.util.Random` or
 * `kotlin.random.Random` so that a deal number gives the same deal on every JDK and Kotlin version (ADR 0138).
 */
internal class SplitMix64(
    seed: Long,
) {
    private var state = seed

    fun nextLong(): Long {
        state += GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    /** Uniform in 0 until [bound], without modulo bias (rejects the incomplete last block of 63-bit values). */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive: $bound" }
        val limit = Long.MAX_VALUE - Long.MAX_VALUE % bound
        while (true) {
            val r = nextLong() ushr 1
            if (r < limit) return (r % bound).toInt()
        }
    }

    private companion object {
        val GAMMA = 0x9E3779B97F4A7C15uL.toLong()
        val MIX_1 = 0xBF58476D1CE4E5B9uL.toLong()
        val MIX_2 = 0x94D049BB133111EBuL.toLong()
    }
}
