package dev.sluide.codeintelligence.semantic

import dev.sluide.fileviewers.StyledRange
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Semantic tokens: relative positions decoded with the legend, the few types and modifiers that get a class. */
class SemanticTokensTest {
    // ty's legend (0.0.99), with the standard `deprecated` modifier added as a server might send it.
    private val legend =
        SemanticTokensLegend(
            listOf("namespace", "class", "parameter", "selfParameter", "clsParameter", "variable", "builtinConstant"),
            listOf("definition", "readonly", "deprecated"),
        )

    @Test
    fun `tokens are decoded relative to the token before, on its line or from the line's start`() {
        val data =
            listOf(
                // line 0, char 4: parameter, definition
                listOf(0, 4, 1, 2, 1),
                // line 0, char 7: selfParameter
                listOf(0, 3, 4, 3, 0),
                // line 2, char 2: builtinConstant, deprecated
                listOf(2, 2, 4, 6, 4),
                // an index outside the legend
                listOf(1, 0, 1, 99, 0),
                // incomplete
                listOf(1, 0),
            ).flatten()
        val tokens = decodeTokens(data, legend)
        assertEquals(
            listOf(
                SemanticToken(0, 4, 1, "parameter", setOf("definition")),
                SemanticToken(0, 7, 4, "selfParameter", emptySet()),
                SemanticToken(2, 2, 4, "builtinConstant", setOf("deprecated")),
                SemanticToken(3, 0, 1, null, emptySet()),
            ),
            tokens,
        )
    }

    @Test
    fun `parameters, self and cls and builtin constants are coloured, deprecated struck, the rest unstyled`() {
        assertEquals(listOf("sem-parameter"), semanticClasses("parameter", setOf("definition")))
        assertEquals(listOf("sem-self"), semanticClasses("selfParameter", emptySet()))
        assertEquals(listOf("sem-self"), semanticClasses("clsParameter", emptySet()))
        assertEquals(listOf("sem-builtin-constant"), semanticClasses("builtinConstant", emptySet()))
        assertEquals(listOf("sem-deprecated"), semanticClasses("function", setOf("deprecated")))
        assertEquals(emptyList(), semanticClasses("variable", setOf("readonly")))
        assertEquals(emptyList(), semanticClasses(null, emptySet()))
    }

    @Test
    fun `tokens become overlay ranges in the text, cut at their line's end, one per class`() {
        val text = "def m(self, v):\n    return v, None\n"
        val tokens =
            listOf(
                SemanticToken(0, 6, 4, "selfParameter", emptySet()),
                SemanticToken(0, 12, 1, "parameter", emptySet()),
                SemanticToken(1, 11, 1, "parameter", emptySet()),
                SemanticToken(1, 14, 9, "builtinConstant", setOf("deprecated")),
                SemanticToken(1, 4, 6, "keyword", emptySet()),
                SemanticToken(9, 0, 3, "parameter", emptySet()),
            )
        assertEquals(
            listOf(
                StyledRange(6, 10, "sem-self"),
                StyledRange(12, 13, "sem-parameter"),
                StyledRange(27, 28, "sem-parameter"),
                StyledRange(30, 34, "sem-builtin-constant"),
                StyledRange(30, 34, "sem-deprecated"),
            ),
            semanticRanges(tokens, text),
        )
    }

    /** A legend as lsp4j parses a server's JSON: a list the JSON lacks stays null. */
    private fun parsedLegend(json: String): SemanticTokensLegend =
        MessageJsonHandler(emptyMap()).gson.fromJson(json, SemanticTokensLegend::class.java)

    @Test
    fun `a legend the JSON leaves without types or modifiers names none`() {
        val data = listOf(0, 1, 2, 0, 1)
        assertEquals(
            listOf(SemanticToken(0, 1, 2, null, emptySet())),
            decodeTokens(data, parsedLegend("{}")),
            "no types, no modifiers",
        )
        assertEquals(
            listOf(SemanticToken(0, 1, 2, "parameter", emptySet())),
            decodeTokens(data, parsedLegend("""{"tokenTypes":["parameter"]}""")),
            "types without modifiers",
        )
    }

    @Test
    fun `a last group of four numbers is left out, not read past the data`() {
        assertEquals(
            listOf(SemanticToken(0, 4, 1, "parameter", emptySet())),
            decodeTokens(listOf(0, 4, 1, 2, 0, 0, 1, 1, 2), legend),
        )
    }

    @Test
    fun `only the modifier bits below the sign bit are read`() {
        val names = (0..31).map { "m$it" }
        val tokens = decodeTokens(listOf(0, 0, 1, 0, -1), SemanticTokensLegend(listOf("t"), names))
        assertEquals((0..30).map { "m$it" }.toSet(), tokens.single().modifiers)
    }

    @Test
    fun `a type that only has the hash code of a coloured one is not coloured`() {
        for (type in listOf("parameter", "selfParameter", "clsParameter", "builtinConstant")) {
            // Changing the last two characters x, y to x + 1, y - 31 keeps the hash code (31 * x + y).
            val impostor = type.dropLast(2) + (type[type.length - 2] + 1) + (type[type.length - 1] - 31)
            assertEquals(type.hashCode(), impostor.hashCode(), type)
            assertNotEquals(type, impostor)
            assertEquals(emptyList(), semanticClasses(impostor, emptySet()), impostor)
        }
    }

    @Test
    fun `a token whose start and length overflow into a range is dropped`() {
        // Int.MIN_VALUE + -1 wraps to Int.MAX_VALUE: the end would clamp to the line's end, past the start.
        val tokens = listOf(SemanticToken(0, Int.MIN_VALUE, -1, "parameter", emptySet()))
        assertEquals(emptyList(), semanticRanges(tokens, "def f(a):\n"))
    }
}
