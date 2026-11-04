package dev.sluide.speech.text

import dev.sluide.speech.engine.SpeechChunk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SentencesTest {
    @Test
    fun `ends a sentence after a period, exclamation or question mark before whitespace or the end`() {
        assertEquals(listOf("One.", "Two!", "Three?", "Four"), texts("One. Two! Three? Four"))
    }

    @Test
    fun `ends a sentence after a run of marks and an ellipsis`() {
        assertEquals(listOf("Really?!", "Well...", "So…", "Yes."), texts("Really?! Well... So… Yes."))
    }

    @Test
    fun `keeps closing quotes and brackets with the sentence`() {
        assertEquals(
            listOf("He said \"Go.\"", "(She left.)", "“Fine!”", "Ok.'", "[Done.]"),
            texts("He said \"Go.\" (She left.) “Fine!” Ok.' [Done.]"),
        )
    }

    @Test
    fun `a mark before a non-space char ends no sentence`() {
        assertEquals(listOf("Pi is 3.14 in v1.2.3.", "Next"), texts("Pi is 3.14 in v1.2.3. Next"))
        assertEquals(listOf("See x.org?q=1 now."), texts("See x.org?q=1 now."))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Mr. Smith", "Mrs. Smith", "Ms. Smith", "Dr. Who", "Prof. X", "Main St. here", "Sam Jr. came",
            "Sam Sr. came", "red vs. blue", "pens, etc. and more", "fruit, e.g. apples", "one, i.e. this",
            "see No. 5", "in Fig. 3", "approx. ten", "cf. the other", "(e.g. this)", "J. R. R. Tolkien",
        ],
    )
    fun `abbreviations and single initials end no sentence`(text: String) {
        assertEquals(listOf("$text end."), texts("$text end."))
    }

    @Test
    fun `No and Fig end a sentence unless a number follows`() {
        assertEquals(listOf("I said no.", "Then"), texts("I said no. Then"))
        assertEquals(listOf("A fig.", "Then"), texts("A fig. Then"))
    }

    @Test
    fun `the word I ends a sentence`() {
        assertEquals(listOf("So did I.", "Then"), texts("So did I. Then"))
    }

    @Test
    fun `an ellipsis before a lowercase word ends no sentence`() {
        assertEquals(listOf("Wait... what is it?", "Wait…", "Then"), texts("Wait... what is it? Wait… Then"))
    }

    @Test
    fun `a hard break ends a sentence and belongs to no chunk`() {
        val spoken = spoken("Title\n\nText without end\n\nMore. Text")

        assertEquals(listOf("Title", "Text without end", "More.", "Text"), Sentences.chunks(spoken).map { it.text })
        assertTrue(Sentences.chunks(spoken).none { '\n' in it.text })
    }

    @Test
    fun `drops empty chunks`() {
        assertEquals(emptyList(), texts(""))
        assertEquals(listOf("A.", "B."), texts("A.\n\nB."))
    }

    @Test
    fun `a chunk's range is its sentence and its text the spoken substring`() {
        val spoken = spoken("One two. Three!")

        assertEquals(
            listOf(SpeechChunk(0, 8, 0, 8, "One two."), SpeechChunk(9, 15, 9, 15, "Three!")),
            Sentences.chunks(spoken),
        )
    }

    @Test
    fun `splits a long first sentence after its first clause mark from 20 chars on`() {
        val first = "Short, then a longer clause; and a third part: all of it more than forty chars."
        val chunks = Sentences.chunks(spoken("$first Next."), firstChunkMaxChars = 40)

        assertEquals(
            listOf("Short, then a longer clause;", "and a third part: all of it more than forty chars.", "Next."),
            chunks.map { it.text },
        )
        assertEquals(
            listOf(0 to first.length, 0 to first.length),
            chunks.take(2).map { it.sentenceStart to it.sentenceEnd },
        )
        assertEquals(29, chunks[1].start)
    }

    @ParameterizedTest
    @ValueSource(strings = ["—", " –"])
    fun `a dash is a clause mark`(dash: String) {
        val chunks =
            Sentences.chunks(
                spoken("A rather long beginning$dash then the rest of it."),
                firstChunkMaxChars = 30,
            )

        assertEquals(listOf("A rather long beginning$dash", "then the rest of it."), chunks.map { it.text })
    }

    @Test
    fun `splits a long first sentence without a clause mark at its last space before the limit`() {
        val chunks = Sentences.chunks(spoken("One two three four five six seven, eight."), firstChunkMaxChars = 20)

        assertEquals(listOf("One two three four", "five six seven, eight."), chunks.map { it.text })
        assertTrue(chunks.all { it.sentenceStart == 0 && it.sentenceEnd == 41 })
    }

    @Test
    fun `a comma inside a number is no clause mark`() {
        val chunks =
            Sentences.chunks(
                spoken("We counted up to exactly 1,000,000 of those things"),
                firstChunkMaxChars = 40,
            )

        assertEquals(listOf("We counted up to exactly 1,000,000 of", "those things"), chunks.map { it.text })
    }

    @Test
    fun `splits only the first sentence and keeps one within the limit whole`() {
        val long = "This one is long enough, to be split if it came first."

        assertEquals(listOf("Short.", long), texts("Short. $long", firstChunkMaxChars = 30))
        assertEquals(listOf("Short."), texts("Short.", firstChunkMaxChars = 6))
    }

    @Test
    fun `keeps a long first word whole`() {
        assertEquals(listOf("Supercalifragilistic"), texts("Supercalifragilistic", firstChunkMaxChars = 5))
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
    fun `chunks cover every spoken non-whitespace char once, in order`(text: String) {
        for (max in listOf(10, 30, 120)) {
            val spoken = spoken(text)
            val chunks = Sentences.chunks(spoken, firstChunkMaxChars = max)

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

    private fun texts(
        text: String,
        firstChunkMaxChars: Int = 120,
    ) = Sentences.chunks(spoken(text), firstChunkMaxChars).map { it.text }
}
