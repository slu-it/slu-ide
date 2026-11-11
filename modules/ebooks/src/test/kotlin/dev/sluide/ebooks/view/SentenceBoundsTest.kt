package dev.sluide.ebooks.view

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SentenceBoundsTest {
    private fun sentence(
        text: String,
        offset: Int,
    ) = SentenceBounds.around(text, offset).let { text.substring(it.first, it.last + 1) }

    @ParameterizedTest
    @CsvSource("0,Hello world.", "5,Hello world.", "11,Hello world.", "14,Second one!", "26,Third?", "31,Third?")
    fun `finds the sentence around the offset`(
        offset: Int,
        expected: String,
    ) {
        assertEquals(expected, sentence("Hello world. Second one! Third?", offset))
    }

    @Test
    fun `a dot inside a word does not end the sentence`() {
        assertEquals("Version 3.14 is out.", sentence("Intro. Version 3.14 is out. Bye.", 15))
    }

    @Test
    fun `never crosses a paragraph break`() {
        val text = "First paragraph without a stop\n\nSecond paragraph without a stop"
        assertEquals("First paragraph without a stop", sentence(text, 3))
        assertEquals("Second paragraph without a stop", sentence(text, 40))
    }

    @Test
    fun `a line break after the terminator ends the sentence`() {
        assertEquals("One.", sentence("One.\nTwo.", 1))
        assertEquals("Two.", sentence("One.\nTwo.", 6))
    }

    @Test
    fun `offsets outside the text are clamped and an empty text has no sentence`() {
        assertEquals("Only one.", sentence("Only one.", 100))
        assertEquals("Only one.", sentence("Only one.", -5))
        assertTrue(SentenceBounds.around("", 0).isEmpty())
    }
}
