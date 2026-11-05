package dev.sluide.translation.text

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholdersTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("candidates")
    fun `the token counts from one`(
        form: String,
        placeholders: Placeholders,
        first: String,
        tenth: String,
    ) {
        assertEquals(first, placeholders.token(0), form)
        assertEquals(tenth, placeholders.token(9), form)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("candidates")
    fun `restore puts the spans back in place`(
        form: String,
        placeholders: Placeholders,
    ) {
        val translated = "Siehe ${placeholders.token(1)} und ${placeholders.token(0)}."

        assertEquals("Siehe B und A.", placeholders.restore(translated, listOf("A", "B")), form)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("candidates")
    fun `a dropped token is appended after a space`(
        form: String,
        placeholders: Placeholders,
    ) {
        val translated = "Siehe ${placeholders.token(1)}."

        assertEquals("Siehe B. A", placeholders.restore(translated, listOf("A", "B")), form)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("candidates")
    fun `a token with spaces inside is still found`(
        form: String,
        placeholders: Placeholders,
        first: String,
    ) {
        val mangled = first.let { it.take(1) + " " + it.drop(1).dropLast(1) + " " + it.takeLast(1) }

        assertEquals("Siehe A.", placeholders.restore("Siehe $mangled.", listOf("A")), form)
    }

    // Only for the default: with the same opening and closing delimiter (`__1__2__`) a lost closer is ambiguous.
    @Test
    fun `a token that lost its closing delimiter is still found and the space after it stays`() {
        val translated = "[1[2]bag [3 end"

        assertEquals("ABbag C end", Placeholders.DEFAULT.restore(translated, listOf("A", "B", "C")))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("candidates")
    fun `a token for a span that does not exist stays and token ten is not token one`(
        form: String,
        placeholders: Placeholders,
    ) {
        val spans = List(10) { "S$it" }
        val translated = "${placeholders.token(9)} ${placeholders.token(0)}"

        assertEquals("S9 S0 S1 S2 S3 S4 S5 S6 S7 S8", placeholders.restore(translated, spans), form)
        assertEquals(
            placeholders.token(4),
            placeholders.restore(placeholders.token(4), listOf("A")).substringBefore(" "),
        )
    }

    @Test
    fun `a span that holds a token is not replaced again`() {
        assertEquals("x {2} y B", Placeholders.CURLY.restore("x {1} y", listOf("{2}", "B")))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("candidates")
    fun `text that looks like a token is protected and comes back verbatim`(
        form: String,
        placeholders: Placeholders,
    ) {
        val token = placeholders.token(0)
        val text = "Use $token as a placeholder in `format`."

        for (markdown in listOf(false, true)) {
            val sentence = TranslationText.segments(text, markdown, placeholders = placeholders).single()
            sentence as Segment.Sentence
            assertEquals(token, sentence.spans.first(), "$form markdown=$markdown")
            assertRoundTrip(text, markdown, placeholders)
        }
        val segments = TranslationText.segments(text, true, placeholders = placeholders)
        assertEquals(text, TranslationText.assemble(text, segments, placeholders) { it.text })
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "See [1](https://example.org/a) for details.",
            "See the [docs][1] for details.",
            "As shown in [1], the claim [2] holds.",
            "Read [the [1] guide](guide.md) first.",
            "See [1, 2] and array[1 here.",
        ],
    )
    fun `Markdown and text that use the square form themselves come back verbatim`(text: String) {
        for (markdown in listOf(false, true)) assertRoundTrip(text, markdown, Placeholders.SQUARE)
    }

    @Test
    fun `restore with no spans returns the translation as is`() {
        assertEquals("a {1}", Placeholders.CURLY.restore("a {1}", emptyList()))
    }

    @Test
    fun `the default is the square form`() {
        assertEquals("[1]", Placeholders.DEFAULT.token(0))
    }

    companion object {
        @JvmStatic
        fun candidates() =
            listOf(
                Arguments.of("curly", Placeholders.CURLY, "{1}", "{10}"),
                Arguments.of("underscores", Placeholders.UNDERSCORES, "__1__", "__10__"),
                Arguments.of("square", Placeholders.SQUARE, "[1]", "[10]"),
            )
    }
}
