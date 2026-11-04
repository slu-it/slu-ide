package dev.sluide.speech.session

import dev.sluide.speech.engine.WordTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PositionMapTest {
    // Two pieces of chunk 3 (a split first sentence 0..40), then chunk 4; 100 frames each.
    private val pieces =
        arrayOf<PlacedPiece?>(
            PlacedPiece(3, 0, 40, 0, listOf(WordTiming(0, 5, 10, 40), WordTiming(6, 12, 50, 90))),
            PlacedPiece(3, 0, 40, 100, listOf(WordTiming(14, 20, 0, 60))),
            PlacedPiece(4, 41, 60, 200, emptyList()),
            null,
        )

    @Test
    fun `no piece placed gives no position`() {
        assertNull(positionAt(pieces, 0, 0))
    }

    @Test
    fun `finds the word whose samples hold the played frame`() {
        assertEquals(ReadingPosition(3, 0, 40, 0, 5), positionAt(pieces, 3, 10))
        assertEquals(ReadingPosition(3, 0, 40, 0, 5), positionAt(pieces, 3, 39))
        assertEquals(ReadingPosition(3, 0, 40, 6, 12), positionAt(pieces, 3, 89))
    }

    @Test
    fun `between and outside words the sentence stays without a word`() {
        assertEquals(ReadingPosition(3, 0, 40, null, null), positionAt(pieces, 3, 0))
        assertEquals(ReadingPosition(3, 0, 40, null, null), positionAt(pieces, 3, 45))
        assertEquals(ReadingPosition(3, 0, 40, null, null), positionAt(pieces, 3, 99))
    }

    @Test
    fun `word samples count from the start of their piece`() {
        assertEquals(ReadingPosition(3, 0, 40, 14, 20), positionAt(pieces, 3, 100))
        assertEquals(ReadingPosition(3, 0, 40, 14, 20), positionAt(pieces, 3, 159))
        assertEquals(ReadingPosition(3, 0, 40, null, null), positionAt(pieces, 3, 160))
    }

    @Test
    fun `the last placed piece holds the frames after it`() {
        assertEquals(ReadingPosition(4, 41, 60, null, null), positionAt(pieces, 3, 200))
        // An underrun: the frames played pass the audio written so far.
        assertEquals(ReadingPosition(4, 41, 60, null, null), positionAt(pieces, 3, 5000))
        // Only the placed pieces count.
        assertEquals(ReadingPosition(3, 0, 40, null, null), positionAt(pieces, 2, 5000))
    }

    @Test
    fun `a piece without audio is passed over`() {
        val withEmpty =
            arrayOf<PlacedPiece?>(
                PlacedPiece(0, 0, 10, 0, emptyList()),
                PlacedPiece(1, 11, 20, 0, listOf(WordTiming(11, 15, 0, 50))),
            )

        assertEquals(ReadingPosition(1, 11, 20, 11, 15), positionAt(withEmpty, 2, 0))
    }
}
