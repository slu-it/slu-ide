package dev.sluide.speech.text

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertTrue

/**
 * Text up to the 1 MiB the tool accepts, in shapes that made a scanner quadratic: each one search from a line or a
 * mark to the end of the document or the paragraph. Asserts how the time grows (docs/testing.md, "Portability"):
 * four times the text takes about four times as long when linear, sixteen times when quadratic. The best of a few
 * runs after a warm-up keeps JIT and GC noise out; a generous cap catches a slow scanner that grows linearly.
 */
class LargeTextTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    fun `prepares and chunks a large text in linear time`(
        name: String,
        shape: Shape,
    ) {
        val quarter = shape.text(MEBIBYTE / 4)
        val full = shape.text(MEBIBYTE)
        read(quarter)

        val quarterNanos = bestOf(quarter)
        val fullNanos = bestOf(full)

        assertTrue(fullNanos < CAP_NANOS, "1 MiB took ${fullNanos / 1_000_000} ms")
        val ratio = fullNanos.toDouble() / maxOf(quarterNanos, 1)
        assertTrue(ratio <= MAX_RATIO, "4x the text took ${"%.1f".format(ratio)}x the time")
    }

    private fun bestOf(text: String): Long =
        (1..RUNS).minOf {
            val start = System.nanoTime()
            read(text)
            System.nanoTime() - start
        }

    private fun read(text: String) {
        for (markdown in listOf(true, false)) {
            val chunks = Sentences.chunks(SpeechText.prepare(text, markdown))
            chunks.firstOrNull()?.let { Sentences.splitForFastStart(it) }
        }
    }

    /** [unit] repeated to half the size after [prefix], then [suffix] repeated (else [unit]) to the size. */
    class Shape(
        private val unit: String,
        private val prefix: String = "",
        private val suffix: String = unit,
    ) {
        fun text(size: Int): String =
            buildString {
                append(prefix)
                while (length < size / 2) append(unit)
                while (length < size) append(suffix)
            }
    }

    private companion object {
        const val MEBIBYTE = 1 shl 20
        const val RUNS = 3
        const val MAX_RATIO = 8.0
        const val CAP_NANOS = 5_000_000_000L

        @JvmStatic
        fun shapes(): List<Arguments> =
            listOf(
                shape("paragraphs, each looked for a table pipe to the end", Shape("Text.\n\n")),
                shape("an unclosed comment, each line looked for its end to the end", Shape("comment\n", "<!--\n")),
                shape("comments in one paragraph, each looked for its end", Shape("a <!-- ")),
                shape("unclosed brackets, each looked for its partner", Shape("see [note\n")),
                shape("links without a closing parenthesis", Shape("[a](b \n")),
                shape("lines starting with a bracket, each looked for a definition label", Shape("[a\n")),
                shape("abbreviations, each mark looked at the rest of the sentence", Shape("Mr. ")),
                shape("a URL with many trailing parentheses", Shape(")", "https://x.org/")),
                shape("emphasis openers of one kind before closers of another", Shape("_a ", suffix = "a* ")),
                shape("backtick runs of two lengths (the index of same-length runs)", Shape("` `` ")),
                shape("plain sentences", Shape("This is a sentence. ")),
            )

        fun shape(
            name: String,
            shape: Shape,
        ): Arguments = Arguments.of(name, shape)
    }
}
