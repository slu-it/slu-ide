package dev.sluide.translation.text

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random

/**
 * Texts of random fragments that start or end Markdown, HTML, URLs and placeholder tokens, in the places where they
 * hurt: every text must still be covered without gaps, and every sentence must come back through the placeholders.
 * The seed is fixed, so a failure repeats; the message holds the text.
 */
class RandomRoundTripTest {
    @ParameterizedTest(name = "markdown = {0}")
    @ValueSource(booleans = [false, true])
    fun `random texts round-trip`(markdown: Boolean) {
        val random = Random(SEED)
        repeat(ROUNDS) {
            val text =
                (
                    1..random.nextInt(
                        1,
                        MAX_FRAGMENTS,
                    )
                ).joinToString("") { FRAGMENTS[random.nextInt(FRAGMENTS.size)] }
            for (placeholders in listOf(Placeholders.CURLY, Placeholders.UNDERSCORES, Placeholders.BRACKETS)) {
                assertRoundTrip(text, markdown, placeholders)
            }
        }
    }

    private companion object {
        const val SEED = 20260611L
        const val ROUNDS = 1500
        const val MAX_FRAGMENTS = 40
        val FRAGMENTS =
            listOf(
                " ",
                "[",
                "](",
                "<!--",
                "-->",
                "\n",
                "> ",
                "- ",
                "| ",
                "---",
                "```",
                "{1}",
                "{2}",
                "__1__",
                "⟦1⟧",
                "http://",
                "@",
                "\r\n",
                "  ",
                "\\",
                "`",
                "text",
                "words",
                "Word.",
                "a.b",
                "#",
                "1. ",
                "~~~",
                "<b>",
                "</b>",
                "|",
                ":",
                "www.",
                "(",
                ")",
                "!",
                "![",
                "[^1]",
                "*",
                "_",
                "\t",
                "    ",
                "x@y.org",
                "<https://a.b>",
                "]: ",
                "===",
            )
    }
}
