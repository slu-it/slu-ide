package dev.sluide.translation.text

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertTrue

/**
 * Text up to the 1 MiB the tool accepts, in shapes that make a scanner quadratic: a search from every line or mark to
 * the end of the document or the line. Asserts how the time grows (docs/testing.md, "Portability"): four times the text
 * takes about four times as long when linear, sixteen times when quadratic. The best of a few runs after a warm-up
 * keeps JIT and GC noise out; a generous cap catches a slow scanner that grows linearly.
 */
class LargeTextTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    fun `splits a large text in linear time`(
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
            val segments = TranslationText.segments(text, markdown)
            TranslationText.assemble(text, segments) { it.text }
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
        const val RUNS = 5
        const val MAX_RATIO = 8.0
        const val CAP_NANOS = 5_000_000_000L

        @JvmStatic
        fun shapes(): List<Arguments> =
            listOf(
                shape("plain sentences", Shape("This is a sentence. ")),
                shape("short paragraphs", Shape("Text.\n\n")),
                shape("an unclosed fence", Shape("code line\n", "```\n")),
                shape("an unclosed comment, each line looked for its end", Shape("comment\n", "<!--\n")),
                shape("unclosed comments in one line", Shape("a <!-- ")),
                shape("an unclosed HTML block", Shape("text\n", "<div>\n")),
                shape("unclosed brackets in one line", Shape("[a ")),
                shape("links without a closing parenthesis", Shape("[a](b ")),
                shape("unclosed backticks of two lengths", Shape("` `` ")),
                shape("lines starting with a bracket", Shape("[a\n")),
                shape("a table of one-cell rows", Shape("| a |\n", "| h |\n|---|\n")),
                shape("a URL with many trailing parentheses", Shape(")", "https://x.org/")),
                shape("www. before a non-alphanumeric", Shape("www.!")),
                shape("a wrapped paragraph", Shape("word word\n")),
                shape("many token-like texts", Shape("[1] ")),
                shape("many at signs", Shape("a@")),
                shape("a long local part", Shape("a", suffix = "a@")),
                shape("abbreviations", Shape("Mr. ")),
                shape("list markers", Shape("- item\n")),
                shape("block quote markers", Shape("> ")),
            )

        fun shape(
            name: String,
            shape: Shape,
        ): Arguments = Arguments.of(name, shape)
    }
}
