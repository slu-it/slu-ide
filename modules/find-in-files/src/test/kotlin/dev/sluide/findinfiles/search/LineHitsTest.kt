package dev.sluide.findinfiles.search

import kotlin.test.Test
import kotlin.test.assertEquals

class LineHitsTest {
    @Test
    fun `a match is numbered by the line breaks before its start`() {
        val text = "a\nb\nneedle\n\nneedle"
        val needle = 4..9
        val last = 12..17

        assertEquals(
            listOf(LineHit(2, 0, "needle", listOf(0 until 6)), LineHit(4, 0, "needle", listOf(0 until 6))),
            lineHits(text, listOf(needle, last)),
        )
    }

    @Test
    fun `a text that starts with a line break starts its second line after it`() {
        assertEquals(listOf(LineHit(1, 0, "ab", listOf(0 until 1))), lineHits("\nab", listOf(1..1)))
    }

    @Test
    fun `a match on the line break itself belongs to the line it ends, without a highlight`() {
        assertEquals(listOf(LineHit(0, 2, "ab", emptyList())), lineHits("ab\ncd", listOf(2..2)))
        assertEquals(listOf(LineHit(0, 0, "", emptyList())), lineHits("\nabc", listOf(0..0)))
    }

    @Test
    fun `matches that start on a line or at its break make one hit`() {
        assertEquals(
            listOf(LineHit(0, 0, "ab", listOf(0 until 1))),
            lineHits("ab\ncd", listOf(0..0, 2..2)),
        )
        assertEquals(
            listOf(LineHit(0, 0, "ab", listOf(0 until 1)), LineHit(1, 0, "cd", listOf(0 until 2))),
            lineHits("ab\ncd", listOf(0..0, 3..4)),
        )
    }

    @Test
    fun `a match in a line of only whitespace keeps the whitespace from it on`() {
        assertEquals(listOf(LineHit(0, 1, "  ", listOf(0 until 2))), lineHits("   ", listOf(1..2)))
    }

    @Test
    fun `a line of exactly the snippet length is not cut`() {
        val line = "a".repeat(SNIPPET_LENGTH - 6) + "needle"
        val hit = lineHits(line, listOf(SNIPPET_LENGTH - 6 until SNIPPET_LENGTH)).single()

        assertEquals(line, hit.snippet)
        assertEquals(listOf(SNIPPET_LENGTH - 6 until SNIPPET_LENGTH), hit.highlights)
    }
}
