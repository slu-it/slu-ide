package dev.sluide.textediting

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SentenceRangesTest {
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

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Das gilt z. B. hier", "Das ist, d. h. hier", "Wir, u. a. Anna", "Anna bzw. Ben", "ca. zehn",
            "Tee usw. hier", "siehe vgl. Seite", "Dr. Müller", "Prof. Müller", "Hr. Müller", "Fr. Müller",
            "nach Abs. drei", "ggf. mehr", "inkl. Steuer", "evtl. später", "bspw. dies",
            "Obst, etc. mehr", "siehe Nr. 5", "auf S. 12",
        ],
    )
    fun `German abbreviations end no sentence`(text: String) {
        assertEquals(listOf("$text ende."), texts("$text ende.", Abbreviations.GERMAN))
    }

    @Test
    fun `German abbreviations are not known to the English default`() {
        assertEquals(listOf("Das gilt ca.", "zehn."), texts("Das gilt ca. zehn."))
    }

    @Test
    fun `a German multi-part abbreviation needs both parts`() {
        assertEquals(listOf("Das war z.", "Anna kam."), texts("Das war z. Anna kam.", Abbreviations.GERMAN))
        assertEquals(listOf("Das war es.", "Dann kam Anna."), texts("Das war es. Dann kam Anna.", Abbreviations.GERMAN))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Das ist z.B. gut", "Es ist d.h. gut", "Wir u.a. Anna", "Es gilt z. B.: Äpfel", "Es ist d. h., dass",
        ],
    )
    fun `German unspaced forms and ones before a clause mark end no sentence`(text: String) {
        assertEquals(listOf("$text ende."), texts("$text ende.", Abbreviations.GERMAN))
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
    fun `a line break ends a sentence and belongs to none`() {
        val text = "Title\n\nText without end\n\nMore. Text"

        assertEquals(listOf("Title", "Text without end", "More.", "Text"), texts(text))
        assertTrue(SentenceRanges.split(text).none { '\n' in text.substring(it) })
    }

    @Test
    fun `drops empty sentences`() {
        assertEquals(emptyList(), texts(""))
        assertEquals(listOf("A.", "B."), texts("A.\n\nB."))
    }

    @Test
    fun `a range's last is inclusive`() {
        assertEquals(listOf(0..7, 9..14), SentenceRanges.split("One two. Three!"))
    }

    @Test
    fun `keeps a long sentence whole`() {
        val long = "A very long sentence, with clauses; and more of them: on and on until it ends at last."

        assertEquals(listOf(long, "Next."), texts("$long Next."))
    }

    @Test
    fun `splits a long range after its first clause mark from 20 chars on`() {
        val first = "Short, then a longer clause; and a third part: all of it more than forty chars."
        val text = "$first Next."

        val pieces = SentenceRanges.splitAtClause(text, SentenceRanges.split(text)[0], maxChars = 40)

        assertEquals(listOf(0..27, 29..first.length - 1), pieces)
    }

    @Test
    fun `splits a later range at its own offsets`() {
        val text = "First. Then a longer sentence, which goes on."

        val pieces = SentenceRanges.splitAtClause(text, SentenceRanges.split(text)[1], maxChars = 30)

        assertEquals(listOf("Then a longer sentence,", "which goes on."), pieces.map { text.substring(it) })
    }

    @ParameterizedTest
    @ValueSource(strings = ["—", " –"])
    fun `a dash is a clause mark`(dash: String) {
        assertEquals(
            listOf("A rather long beginning$dash", "then the rest of it."),
            clauses("A rather long beginning$dash then the rest of it.", maxChars = 30),
        )
    }

    @Test
    fun `splits a long range without a clause mark at its last space before the limit`() {
        assertEquals(
            listOf("One two three four", "five six seven, eight."),
            clauses("One two three four five six seven, eight.", 20),
        )
    }

    @Test
    fun `a comma inside a number is no clause mark`() {
        assertEquals(
            listOf("We counted up to exactly 1,000,000 of", "those things"),
            clauses("We counted up to exactly 1,000,000 of those things", maxChars = 40),
        )
    }

    @Test
    fun `keeps a range within the limit or without a space whole`() {
        assertEquals(listOf("Short."), clauses("Short.", maxChars = 6))
        assertEquals(listOf("Supercalifragilistic"), clauses("Supercalifragilistic", maxChars = 5))
    }

    @Test
    fun `splits at 120 chars by default`() {
        val words = (1..30).joinToString(" ") { "word" }
        val range = SentenceRanges.split(words)[0]

        assertEquals(2, SentenceRanges.splitAtClause(words, range).size)
        assertEquals(listOf(0..119), SentenceRanges.splitAtClause(words.take(120), 0..119))
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
    fun `ranges cover every non-whitespace char once, in order, also when split`(text: String) {
        for (max in listOf(10, 30, 120)) {
            val ranges = SentenceRanges.split(text).flatMap { SentenceRanges.splitAtClause(text, it, max) }

            assertEquals(
                text.filterNot(Char::isWhitespace),
                ranges.joinToString("") { text.substring(it).filterNot(Char::isWhitespace) },
            )
            ranges.zipWithNext().forEach { (a, b) -> assertTrue(a.last < b.first, "$a before $b") }
            ranges.forEach { assertEquals(text.substring(it).trim(), text.substring(it)) }
        }
    }

    private fun texts(
        text: String,
        abbreviations: Set<String> = Abbreviations.ENGLISH,
    ) = SentenceRanges.split(text, abbreviations).map { text.substring(it) }

    /** The pieces of the first sentence of [text]. */
    private fun clauses(
        text: String,
        maxChars: Int,
    ) = SentenceRanges.splitAtClause(text, SentenceRanges.split(text)[0], maxChars).map { text.substring(it) }
}
