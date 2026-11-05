package dev.sluide.translation.text

import dev.sluide.textediting.Abbreviations
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslationTextTest {
    @Test
    fun `plain text is split into sentences with the whitespace kept`() {
        val text = "Hello world.  How are you?\n\n  Fine, thanks!\n"

        assertEquals(listOf("Hello world.", "How are you?", "Fine, thanks!"), sentences(text, false))
        assertEquals(listOf("  ", "\n\n  ", "\n"), kept(text, false))
        assertRoundTrip(text, false)
    }

    @Test
    fun `plain mode treats Markdown as text`() {
        assertEquals(listOf("# Title", "- item"), sentences("# Title\n- item", false))
    }

    @Test
    fun `an empty text has no segments`() {
        assertEquals(emptyList(), TranslationText.segments("", false))
        assertEquals(emptyList(), TranslationText.segments("", true))
    }

    @Test
    fun `abbreviations of the given set end no sentence`() {
        val text = "Das ist z. B. ein Test. Dann geht es weiter."

        val german = TranslationText.segments(text, false, Abbreviations.GERMAN).filterIsInstance<Segment.Sentence>()

        assertEquals(listOf("Das ist z. B. ein Test.", "Dann geht es weiter."), german.map { it.text })
    }

    @Test
    fun `a bare URL and an e-mail address are protected spans`() {
        val text = "See https://example.org/a?b=1, or www.example.com. Mail jane.doe@example.org now."

        val segments = TranslationText.segments(text, false).filterIsInstance<Segment.Sentence>()

        assertEquals(
            listOf("See [1], or [2].", "Mail [1] now."),
            segments.map { it.text },
        )
        assertEquals(listOf("https://example.org/a?b=1", "www.example.com"), segments[0].spans)
        assertEquals(listOf("jane.doe@example.org"), segments[1].spans)
        assertRoundTrip(text, false)
    }

    @Test
    fun `a URL keeps its inner parentheses but not the one that closes the sentence`() {
        val segment =
            TranslationText.segments("(see https://en.wikipedia.org/wiki/Foo_(bar))", false).single {
                it is Segment.Sentence
            } as Segment.Sentence

        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)"), segment.spans)
        assertEquals("(see [1])", segment.text)
    }

    @ParameterizedTest
    @ValueSource(strings = ["12345", "... !!!", "https://example.org", "42.5 % - 7", "🙂"])
    fun `a stretch with nothing to translate is kept`(text: String) {
        val segments = TranslationText.segments(text, false)

        assertEquals(listOf(Segment.Kept(text.indices)), segments)
    }

    @Test
    fun `a sentence not translated yet contributes nothing`() {
        val text = "One. Two. Three."
        val segments = TranslationText.segments(text, false)

        val result = TranslationText.assemble(text, segments) { if (it.text == "Two.") null else it.text.uppercase() }

        assertEquals("ONE.  THREE.", result)
    }

    @Test
    fun `assemble puts the spans back through the placeholders`() {
        val text = "Open https://example.org now."
        val segments = TranslationText.segments(text, false)

        val result = TranslationText.assemble(text, segments) { "Jetzt [1] oeffnen." }

        assertEquals("Jetzt https://example.org oeffnen.", result)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "\n", "\r\n", "a", "Line one.\r\nLine two.\r\n", "\n\n\nText with trailing blanks   \n   ",
            "Mr. Smith went to Washington. He said: \"Hello.\" Then left…", "Ümlaut öffnet. Ärger!",
        ],
    )
    fun `every fixture round-trips in both modes`(text: String) {
        assertRoundTrip(text, false)
        assertRoundTrip(text, true)
    }

    @Test
    fun `the segments of a text never overlap`() {
        val segments = TranslationText.segments("A. B.\nC.", false)

        assertTrue(segments.zipWithNext().all { (a, b) -> a.range.last + 1 == b.range.first })
    }
}
