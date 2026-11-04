package dev.sluide.speech.kokoro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
