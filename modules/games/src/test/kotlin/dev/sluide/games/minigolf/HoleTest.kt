package dev.sluide.games.minigolf

import dev.sluide.games.minigolf.geometry.Segment
import dev.sluide.games.minigolf.geometry.Vec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class HoleTest {
    @Test
    fun `walls hold the outlines of greens and blocks and the loose walls, which are kept apart for painting`() {
        val hole =
            testHole {
                block(rect(100, 100, 120, 120))
                wall(200 to 40, 240 to 80)
            }
        val loose = Segment(Vec(200.0, 40.0), Vec(240.0, 80.0))
        assertEquals(listOf(loose), hole.looseWalls)
        assertEquals(hole.greens.single().edges() + hole.blocks.single().edges() + loose, hole.walls)
    }

    @Test
    fun `a slope must pull harder than the slope friction`() {
        assertThrows<IllegalArgumentException> {
            testHole {
                slope(
                    rect(100, 40, 200, 160),
                    0 to Rules.SLOPE_DECELERATION,
                )
            }
        }
    }
}
