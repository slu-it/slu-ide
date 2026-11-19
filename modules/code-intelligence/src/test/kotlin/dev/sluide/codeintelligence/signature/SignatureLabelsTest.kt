package dev.sluide.codeintelligence.signature

import dev.sluide.codeintelligence.ui.DocBlock
import dev.sluide.codeintelligence.ui.DocSpan
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
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
        start: Int?,
        end: Int?,
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
    fun `a parameter label found only inside longer words is the first place after the parenthesis`() {
        // LSP 3.17: a parameter label string is a substring of the signature label.
        assertEquals(1 until 2, parameterRange("(xa, xb)", ParameterInformation("x")))
        assertEquals(4 until 5, parameterRange("max(xx)", ParameterInformation("x")), "not the x of max")
        assertEquals(0 until 4, parameterRange("path, sep", ParameterInformation("path")), "no parenthesis")
        assertEquals(0 until 1, parameterRange("a", ParameterInformation("a")), "the whole label")
    }

    @Test
    fun `a parameter label that starts or ends with a symbol is whole at a word next to it`() {
        assertEquals(2 until 4, parameterRange("(x*a, *a)", ParameterInformation("*a")))
        assertEquals(1 until 3, parameterRange("(a*b, a*)", ParameterInformation("a*")))
    }

    @Test
    fun `a parameter label the JSON leaves null, empty or without a start or end is none`() {
        val gson = MessageJsonHandler(emptyMap()).gson
        val nullLabel = gson.fromJson("""{"label":null}""", ParameterInformation::class.java)
        assertNull(parameterRange("(a)", nullLabel))
        assertNull(parameterRange("(a)", ParameterInformation()), "no label")
        assertNull(parameterRange("(a)", ParameterInformation(""))) // empty
        assertNull(parameterRange("(a, b)", offsets(null, 3)))
        assertNull(parameterRange("(a, b)", offsets(1, null)))
    }

    @Test
    fun `an answer the JSON leaves incomplete shows what it has`() {
        val noParameters = SignatureInformation().apply { activeParameter = 0 }
        assertEquals(
            SignatureShown("", null, null, emptyList()),
            signatureShown(SignatureHelp(listOf(noParameters), null, null)),
        )
        val two = SignatureInformation("(a, b)", "", listOf(ParameterInformation("a"), ParameterInformation("b")))
        val other = SignatureInformation("(c)", "", listOf(ParameterInformation("c")))
        assertEquals(
            "(a, b)",
            signatureShown(SignatureHelp(listOf(two, other), null, null))?.label,
            "first without index",
        )
        assertEquals("(c)", signatureShown(SignatureHelp(listOf(two, other), 9, null))?.label, "clamped to the last")
        assertEquals(
            "(a, b)",
            signatureShown(SignatureHelp(listOf(two, other), -3, null))?.label,
            "clamped to the first",
        )
        assertNull(signatureShown(SignatureHelp(listOf(two), 0, -1))?.active, "a negative parameter index is none")
        assertEquals(4 until 5, signatureShown(SignatureHelp(listOf(two), null, 1))?.active)
    }

    @Test
    fun `parameter docs and signature docs are separated by a rule only when both exist`() {
        val paragraph = { text: String -> DocBlock.Paragraph(listOf(DocSpan(text))) }
        val both = SignatureInformation("(a)", "Sig.", listOf(ParameterInformation("a", "Par.")))
        val parameterOnly = SignatureInformation("(a)", "", listOf(ParameterInformation("a", "Par.")))
        val signatureOnly = SignatureInformation("(a)", "Sig.", listOf(ParameterInformation("a")))
        assertEquals(
            listOf(paragraph("Par."), DocBlock.Rule, paragraph("Sig.")),
            signatureShown(SignatureHelp(listOf(both), 0, 0))?.docs,
        )
        assertEquals(listOf(paragraph("Par.")), signatureShown(SignatureHelp(listOf(parameterOnly), 0, 0))?.docs)
        assertEquals(listOf(paragraph("Sig.")), signatureShown(SignatureHelp(listOf(signatureOnly), 0, 0))?.docs)
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
