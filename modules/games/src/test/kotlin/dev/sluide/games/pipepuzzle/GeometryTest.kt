package dev.sluide.games.pipepuzzle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeometryTest {
    @Test
    fun `a clockwise turn moves each opening one side on, N to E to S to W to N`() {
        assertEquals(Openings.of(Dir.E), Openings.of(Dir.N).rotatedCw())
        assertEquals(Openings.of(Dir.S), Openings.of(Dir.E).rotatedCw())
        assertEquals(Openings.of(Dir.W), Openings.of(Dir.S).rotatedCw())
        assertEquals(Openings.of(Dir.N), Openings.of(Dir.W).rotatedCw())
        assertEquals(Openings.of(Dir.E, Dir.S), Openings.of(Dir.N, Dir.E).rotatedCw())
        assertEquals(Openings.of(Dir.N, Dir.E, Dir.S), Openings.of(Dir.W, Dir.N, Dir.E).rotatedCw())
    }

    @Test
    fun `a counter-clockwise turn undoes a clockwise one and four turns are none`() {
        for (bits in 0..15) {
            val tile = Openings(bits)
            assertEquals(tile, tile.rotatedCw().rotatedCcw())
            assertEquals(
                tile,
                tile
                    .rotatedCw()
                    .rotatedCw()
                    .rotatedCw()
                    .rotatedCw(),
            )
            assertEquals(tile.count, tile.rotatedCw().count)
        }
    }

    @Test
    fun `the distinct turns of each tile type`() {
        assertEquals(4, Openings.of(Dir.N).rotations.size)
        assertEquals(listOf(Openings.of(Dir.N, Dir.S), Openings.of(Dir.E, Dir.W)), Openings.of(Dir.N, Dir.S).rotations)
        assertEquals(4, Openings.of(Dir.N, Dir.E).rotations.size)
        assertEquals(4, Openings.of(Dir.N, Dir.E, Dir.S).rotations.size)
        assertEquals(listOf(Openings.CROSS), Openings.CROSS.rotations)
    }

    @Test
    fun `bits, has, count and opposite`() {
        val corner = Openings(Dir.N.bit or Dir.W.bit)
        assertEquals(9, corner.bits)
        assertTrue(corner.has(Dir.N))
        assertTrue(corner.has(Dir.W))
        assertFalse(corner.has(Dir.E))
        assertEquals(2, corner.count)
        assertEquals(0, Openings.NONE.count)
        assertEquals(listOf(Dir.S, Dir.W, Dir.N, Dir.E), Dir.entries.map { it.opposite })
        assertEquals(Pos(2, 4), Pos(2, 3).step(Dir.E))
        assertEquals(Pos(1, 3), Pos(2, 3).step(Dir.N))
    }

    @Test
    fun `two tiles match when both open toward each other, and never toward the board edge`() {
        val tiles =
            board(
                "╶┐",
                "╶┘",
            )

        assertTrue(Flow.matches(2, tiles, Pos(0, 0), Dir.E), "end meets corner")
        assertTrue(Flow.matches(2, tiles, Pos(0, 1), Dir.W), "and back")
        assertTrue(Flow.matches(2, tiles, Pos(0, 1), Dir.S))
        assertFalse(Flow.matches(2, tiles, Pos(0, 0), Dir.S), "no opening")
        assertTrue(Flow.matches(2, tiles, Pos(1, 1), Dir.W), "end meets corner at the bottom")
        val outward = board("╴╷", "╶┘")
        assertFalse(Flow.matches(2, outward, Pos(0, 0), Dir.W), "toward the edge")
        assertTrue(Flow.matches(2, outward, Pos(0, 1), Dir.S), "the corner below opens north")
        val oneSided = board("╶╷", "╶┘")
        assertFalse(Flow.matches(2, oneSided, Pos(0, 0), Dir.E), "the neighbour does not open back")
    }
}
