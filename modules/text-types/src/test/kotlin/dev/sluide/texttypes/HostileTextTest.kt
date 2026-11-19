package dev.sluide.texttypes

import dev.sluide.texttypes.TextTypeDetector.Companion.RULES_LIMIT
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

/**
 * Texts built to make the rules' patterns backtrack or recurse: the whole [RULES_LIMIT] filled with one piece repeated,
 * or a keyword of a pattern followed by one long run of a single kind of char. The built-in rules must answer within
 * a second, which a pattern that is quadratic in a line's length does not, and without a StackOverflowError (which
 * the detector would not catch).
 */
class HostileTextTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "(", "[", "<", "{a:", "**a ", "[a](", "import a,", "import a.", "private ", "public ", "def f(", "):->",
            "function(a:", "<!--", "a:\n", "a:\n ", "`", "| --- ", "'", "\"", "@@ -1 +1 @@\n", "val a: A = ",
            "class A(val ", "when { ", "a!! ", "\$a", "\\", "for a in ", "a = b!!\n",
        ],
    )
    fun `a long repetition of a piece is answered quickly`(piece: String) {
        answersQuickly(piece.repeat(RULES_LIMIT / piece.length + 1))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("prefixedRuns")
    fun `a keyword followed by one long run is answered quickly`(
        label: String,
        text: String,
    ) {
        answersQuickly(text)
    }

    private fun answersQuickly(text: String) {
        assertTimeoutPreemptively(Duration.ofSeconds(1)) { TextTypeDetector().detect(text) }
    }

    companion object {
        private val prefixes =
            listOf(
                "",
                "x",
                "function",
                "function x",
                "when",
                "import ",
                "import a",
                "for ",
                "for a",
                "from ",
                "class A",
                "class A(",
                "def f(",
                "fun ",
                "fun f(",
                "val a",
                "var a: ",
                "package ",
                "private ",
                "public ",
                "public static ",
                "public A ",
                "interface A",
                "type A",
                "export ",
                "const ",
                "let ",
                "let a",
                "const a",
                "var a = ",
                "with ",
                "with a",
                "with a(",
                "if __name__",
                "SELECT ",
                "SELECT a",
                "SELECT *",
                "UPDATE t ",
                "CREATE ",
                "WITH x ",
                "resource ",
                "param a ",
                "targetScope",
                "@@ -",
                "--- ",
                "# ",
                "- ",
                "| ",
                "> ",
                "**",
                "[a",
                "```",
                "---\n",
                "---\na",
                ".a {",
                "a {\n",
                "@media",
                "--a",
                "-fx-a",
                "color",
                "<",
                "<?xml",
                "<!--",
                "<!DOCTYPE",
                "#!",
                "graph",
                "pie",
                "f\"",
                "require(",
                "a!!",
                "a = b",
                "a ?: ",
                "a: ",
                "a?: ",
                "readonly a",
                "private a",
                "@Override",
                "a\n",
                "a:\n",
                "a:\n ",
            )

        /** Each prefix, a run of one char up to the rule limit, and a char that ends no pattern. */
        @JvmStatic
        fun prefixedRuns(): List<Arguments> =
            prefixes.flatMap { prefix ->
                listOf(" ", "a", ".", ":", ",", "(", "-", "1", "!").map { run ->
                    val label = "${prefix.replace("\n", "\\n")}[$run…]~"
                    Arguments.of(label, prefix + run.repeat(RULES_LIMIT - prefix.length - 1) + "~")
                }
            }
    }
}
