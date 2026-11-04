package dev.sluide.speech.kokoro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KokoroVocabTest {
    private val vocab = KokoroVocab.load()

    @Test
    fun `punctuation and space ids`() {
        val expected =
            mapOf(
                ';' to 1L,
                ':' to 2L,
                ',' to 3L,
                '.' to 4L,
                '!' to 5L,
                '?' to 6L,
                '—' to 9L,
                '…' to 10L,
                ' ' to 16L,
            )
        for ((c, id) in expected) assertEquals(id, vocab.id(c), "id of '$c'")
    }

    @Test
    fun `phoneme ids`() {
        assertEquals(123L, vocab.id('ɹ'))
        assertEquals(156L, vocab.id('ˈ'))
    }

    @Test
    fun `unknown characters have no id`() {
        assertNull(vocab.id('g'))
        assertNull(vocab.id('7'))
    }

    @Test
    fun `matches compares the entries with the model's vocab`() {
        val own = KokoroVocab.load()
        val entries = HashMap<String, Long>()
        for (c in Char.MIN_VALUE..Char.MAX_VALUE) own.id(c)?.let { entries[c.toString()] = it }
        assertTrue(own.matches(entries))
        assertFalse(own.matches(entries + (";" to 99L)))
        assertFalse(own.matches(entries + ("§" to 300L)))
        assertFalse(own.matches(entries - ";"))
    }
}
