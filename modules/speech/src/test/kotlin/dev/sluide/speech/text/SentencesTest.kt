package dev.sluide.speech.text

import dev.sluide.speech.engine.SpeechChunk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sentence rules are tested in `SentenceRangesTest` (text-editing); this checks the chunk mapping. */
class SentencesTest {
    @Test
    fun `a chunk's range is its sentence and its text the spoken substring`() {
        val spoken = spoken("One two. Three!")

        assertEquals(
            listOf(SpeechChunk(0, 8, 0, 8, "One two."), SpeechChunk(9, 15, 9, 15, "Three!")),
            Sentences.chunks(spoken),
        )
    }

    @Test
    fun `an abbreviation ends no chunk`() {
        assertEquals(
            listOf("Mr. Smith left.", "Then"),
            Sentences.chunks(spoken("Mr. Smith left. Then")).map { it.text },
        )
    }

    @Test
    fun `splits a long chunk after its first clause mark from 20 chars on`() {
        val first = "Short, then a longer clause; and a third part: all of it more than forty chars."
        val chunks = Sentences.chunks(spoken("$first Next."))

        val pieces = Sentences.splitForFastStart(chunks[0], maxChars = 40)

        assertEquals(
            listOf("Short, then a longer clause;", "and a third part: all of it more than forty chars."),
            pieces.map { it.text },
        )
        assertEquals(listOf(0 to first.length, 0 to first.length), pieces.map { it.sentenceStart to it.sentenceEnd })
        assertEquals(listOf(0 to 28, 29 to first.length), pieces.map { it.start to it.end })
    }

    @Test
    fun `splits a later chunk at its own offsets`() {
        val spoken = spoken("First. Then a longer sentence, which goes on.")
        val second = Sentences.chunks(spoken)[1]

        val pieces = Sentences.splitForFastStart(second, maxChars = 30)

        assertEquals(listOf("Then a longer sentence,", "which goes on."), pieces.map { it.text })
        pieces.forEach { assertEquals(spoken.text.substring(it.start, it.end), it.text) }
        assertTrue(pieces.all { it.sentenceStart == second.start && it.sentenceEnd == second.end })
    }

    @Test
    fun `a chunk within the limit comes back alone`() {
        val chunk = Sentences.chunks(spoken("Short."))[0]

        assertEquals(listOf(chunk), Sentences.splitForFastStart(chunk, maxChars = 6))
    }

    @Test
    fun `splits at 120 chars by default`() {
        val words = (1..30).joinToString(" ") { "word" }
        val chunk = Sentences.chunks(spoken(words))[0]

        val within = chunk.copy(end = 120, text = words.take(120))
        assertEquals(listOf(within), Sentences.splitForFastStart(within))
        assertEquals(2, Sentences.splitForFastStart(chunk).size)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "One. Two! Three? Four",
            "Mr. Smith said \"Hi.\" (Then left.)\nNew paragraph... and more… Final",
            "A very long first sentence, which goes on and on: it has clauses; and more of them — until it ends. Short.",
            "Title\n\nItem one\n\nItem two\n\nRow, cell",
            "x",
            "Wait… What? Done.",
        ],
    )
    fun `chunks cover every spoken non-whitespace char once, in order, also when split`(text: String) {
        for (max in listOf(10, 30, 120)) {
            val spoken = spoken(text)
            val chunks = Sentences.chunks(spoken).flatMap { Sentences.splitForFastStart(it, max) }

            assertEquals(
                spoken.text.filterNot(Char::isWhitespace),
                chunks.joinToString("") { it.text.filterNot(Char::isWhitespace) },
            )
            chunks.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start, "$a before $b") }
            chunks.forEach { chunk ->
                assertEquals(spoken.text.substring(chunk.start, chunk.end), chunk.text)
                assertEquals(chunk.text.trim(), chunk.text)
                assertTrue(chunk.sentenceStart <= chunk.start && chunk.end <= chunk.sentenceEnd)
            }
        }
    }

    private fun spoken(text: String) = SpeechText.prepare(text, markdown = false)
}
