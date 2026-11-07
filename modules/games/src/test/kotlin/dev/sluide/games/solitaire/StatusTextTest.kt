package dev.sluide.games.solitaire

import kotlin.test.Test
import kotlin.test.assertEquals

class StatusTextTest {
    @Test
    fun `numbers are grouped, deal numbers are not`() {
        assertEquals("Deal #48213 · High score: 4,210", StatusText.playing(48213, 4210))
        assertEquals("Deal #48213 · High score: -1,050", StatusText.playing(48213, -1050))
        assertEquals("Deal #7", StatusText.playing(7, null))
        assertEquals("Won with 4,210 points in 6:12 — new high score!", StatusText.won(4210, 372_999, true))
        assertEquals("Won with 812 points in 1:02:03", StatusText.won(812, 3_723_000, false))
    }

    @Test
    fun `undo counts`() {
        assertEquals("No winning line from here — undo 1 move", StatusText.undoBack(1))
        assertEquals("No winning line from here — undo 2 moves", StatusText.undoBack(2))
    }
}
