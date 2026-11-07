package dev.sluide.games.blockbreaker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class PowerUpsTest {
    @ParameterizedTest
    @CsvSource(
        "0.0,WIDE",
        "0.349,WIDE",
        "0.35,MULTI",
        "0.649,MULTI",
        "0.65,SLOW",
        "0.899,SLOW",
        "0.9,LIFE",
        "0.999,LIFE",
    )
    fun `the roll picks the kind`(
        roll: Double,
        kind: PowerUp,
    ) {
        assertEquals(kind, Capsule.kindFor(roll))
    }

    @Test
    fun `a dropped capsule is centred on its brick and falls one pixel per step`() {
        val capsule = Capsule.dropFrom(2, 1, PowerUp.SLOW)
        // brick x 52..76, y 40..48; capsule 12 x 6
        assertEquals(58.0, capsule.x)
        assertEquals(41.0, capsule.y)
        capsule.fall()
        assertEquals(42.0, capsule.y)
    }

    @ParameterizedTest
    @CsvSource(
        "150,215,true",
        "150,213,false",
        "128,215,false",
        "129,215,true",
        "180,215,false",
        "179,215,true",
        "150,226,false",
    )
    fun `a capsule overlaps the paddle at 140 to 180 and 220 to 226`(
        x: Double,
        y: Double,
        overlaps: Boolean,
    ) {
        assertEquals(overlaps, Capsule(x, y, PowerUp.WIDE).overlapsPaddle(140.0, 40.0))
    }

    @Test
    fun `a capsule is out when its top passes the field bottom`() {
        assertFalse(Capsule(0.0, 240.0, PowerUp.WIDE).isOut)
        assertTrue(Capsule(0.0, 240.5, PowerUp.WIDE).isOut)
    }
}
