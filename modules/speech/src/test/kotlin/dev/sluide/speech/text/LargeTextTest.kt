package dev.sluide.speech.text

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration

/**
 * Text up to the 1 MiB the tool accepts, in shapes that made a scanner quadratic: each one search from a line or a
 * mark to the end of the document or the paragraph. Linear, each takes under 0.1 s here; quadratic, they took 1 to
 * 15 s. The limit keeps a margin of 10x for slower runners (docs/testing.md, "Portability").
 */
class LargeTextTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    fun `prepares and chunks a large text in linear time`(
        shape: String,
        text: String,
    ) {
        assertTimeoutPreemptively(LIMIT) {
            for (markdown in listOf(true, false)) {
                val chunks = Sentences.chunks(SpeechText.prepare(text, markdown))
                chunks.firstOrNull()?.let { Sentences.splitForFastStart(it) }
            }
        }
    }

    private companion object {
        const val MEBIBYTE = 1 shl 20
        val LIMIT: Duration = Duration.ofSeconds(1)

        @JvmStatic
        fun shapes(): List<Arguments> =
            listOf(
                shape("paragraphs, each looked for a table pipe to the end", "Text.\n\n"),
                shape("an unclosed comment, each line looked for its end to the end", "comment\n", "<!--\n"),
                shape("comments in one paragraph, each looked for its end", "a <!-- "),
                shape("unclosed brackets, each looked for its partner", "see [note\n"),
                shape("links without a closing parenthesis", "[a](b \n"),
                shape("lines starting with a bracket, each looked for a definition label", "[a\n"),
                shape("abbreviations, each mark looked at the rest of the sentence", "Mr. "),
                shape("a URL with many trailing parentheses", ")", "https://x.org/"),
                shape("emphasis openers of one kind before closers of another", "_a ", suffix = "a* "),
                shape("backtick runs", "` `` "),
                shape("plain sentences", "This is a sentence. "),
            )

        /** [unit] repeated to half a MiB after [prefix], then [suffix] repeated (else [unit]) to a MiB. */
        fun shape(
            name: String,
            unit: String,
            prefix: String = "",
            suffix: String = unit,
        ): Arguments {
            val text =
                buildString {
                    append(prefix)
                    while (length < MEBIBYTE / 2) append(unit)
                    while (length < MEBIBYTE) append(suffix)
                }
            return Arguments.of(name, text)
        }
    }
}
