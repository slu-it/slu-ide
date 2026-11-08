package dev.sluide.codeintelligence.signature

import dev.sluide.codeintelligence.ui.DocBlock
import dev.sluide.codeintelligence.ui.DocSpan
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Tuple
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which signature the popup shows, which characters of it are the active parameter, the count and the docs. */
class SignatureLabelsTest {
    private fun offsets(
        start: Int,
        end: Int,
    ) = ParameterInformation().apply { label = Either.forRight(Tuple.two(start, end)) }

    @ParameterizedTest
    @CsvSource(
        // label, parameter label, start, end (-1: none)
        "'(a: int, b: str) -> None', 'b: str', 9, 15",
        "'max(x, y)', 'x', 4, 5",
        "'(path, pathsep)', 'pathsep', 7, 14",
        "'(pathsep, path)', 'path', 10, 14",
        "'(sep, *, key)', 'missing', -1, -1",
    )
    fun `a parameter label as text is found after the parenthesis, as a whole word if it can be`(
        label: String,
        parameter: String,
        start: Int,
        end: Int,
    ) {
        val range = parameterRange(label, ParameterInformation(parameter))
        assertEquals(if (start < 0) null else start until end, range)
    }

    @Test
    fun `a parameter label as offsets is taken as it is, clamped, empty is none`() {
        assertEquals(1 until 7, parameterRange("(a: int)", offsets(1, 7)))
        assertEquals(1 until 8, parameterRange("(a: int)", offsets(1, 99)))
        assertNull(parameterRange("(a: int)", offsets(3, 3)))
    }

    @Test
    fun `the active signature and parameter, the count, and the parameter's docs before the signature's`() {
        val first = SignatureInformation("(a)", "", listOf(ParameterInformation("a")))
        val second =
            SignatureInformation(
                "(a, b)",
                "Adds.",
                listOf(ParameterInformation("a"), ParameterInformation("b", "The b.")),
            )
        val help = SignatureHelp(listOf(first, second), 1, 1)
        assertEquals(
            SignatureShown(
                label = "(a, b)",
                active = 4 until 5,
                count = "2 of 2",
                docs =
                    listOf(
                        DocBlock.Paragraph(listOf(DocSpan("The b."))),
                        DocBlock.Rule,
                        DocBlock.Paragraph(listOf(DocSpan("Adds."))),
                    ),
            ),
            signatureShown(help),
        )
    }

    @Test
    fun `a signature's own active parameter wins, an out of range one is none, no signatures is nothing`() {
        val signature = SignatureInformation("(a, b)", "", listOf(ParameterInformation("a"), ParameterInformation("b")))
        signature.activeParameter = 0
        assertEquals(1 until 2, signatureShown(SignatureHelp(listOf(signature), 0, 1))?.active)
        signature.activeParameter = 5
        val shown = signatureShown(SignatureHelp(listOf(signature), 7, 1))
        assertNull(shown?.active)
        assertNull(shown?.count)
        assertNull(signatureShown(SignatureHelp(emptyList(), 0, 0)))
        assertNull(signatureShown(null))
    }
}
