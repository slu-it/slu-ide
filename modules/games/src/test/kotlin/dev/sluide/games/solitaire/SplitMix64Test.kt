package dev.sluide.games.solitaire

import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitMix64Test {
    @Test
    fun `gives the reference values of SplitMix64`() {
        val random = SplitMix64(1234567)
        val expected = listOf(6457827717110365317uL, 3203168211198807973uL, 9817491932198370423uL)
        assertEquals(expected, List(3) { random.nextLong().toULong() })
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 7, 52, 104, Int.MAX_VALUE])
    fun `nextInt stays below its bound and reaches every value of a small bound`(bound: Int) {
        val random = SplitMix64(42)
        val values = List(2_000) { random.nextInt(bound) }
        assertTrue(values.all { it in 0 until bound })
        if (bound <= 7) assertEquals((0 until bound).toSet(), values.toSet())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `nextInt refuses a bound below 1`(bound: Int) {
        assertThrows<IllegalArgumentException> { SplitMix64(1).nextInt(bound) }
    }
}
