package dev.sluide.codeintelligence.semantic

import dev.sluide.fileviewers.StyledRange
import org.eclipse.lsp4j.SemanticTokensLegend
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

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
}
