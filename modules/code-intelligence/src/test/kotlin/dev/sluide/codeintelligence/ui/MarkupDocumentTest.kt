package dev.sluide.codeintelligence.ui

import dev.sluide.codeintelligence.ui.DocBlock.Code
import dev.sluide.codeintelligence.ui.DocBlock.Heading
import dev.sluide.codeintelligence.ui.DocBlock.Items
import dev.sluide.codeintelligence.ui.DocBlock.Paragraph
import dev.sluide.codeintelligence.ui.DocBlock.Quote
import dev.sluide.codeintelligence.ui.DocBlock.Rule
import dev.sluide.codeintelligence.ui.SpanStyle.CODE
import dev.sluide.codeintelligence.ui.SpanStyle.EMPHASIS
import dev.sluide.codeintelligence.ui.SpanStyle.STRONG
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkedString
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The documentation of hovers, completion items and signatures as blocks for the popups: Markdown as the language
 * servers send it (ty: code fences, HTML entities, escaped underscores), plain text, the old marked strings.
 */
class MarkupDocumentTest {
    private fun text(vararg spans: DocSpan) = Paragraph(spans.toList())

    @Test
    fun `a hover as ty sends it, a code fence, a rule, entities and escapes decoded`() {
        val markdown =
            "```python\ndef join(a: str) -> str\n```\n---\n" +
                "Join&nbsp;two paths, `a` &gt; b\\_c &amp; d&#39;s."
        assertEquals(
            listOf(
                Code("def join(a: str) -> str"),
                Rule,
                text(DocSpan("Join two paths, "), DocSpan("a", setOf(CODE)), DocSpan(" > b_c & d's.")),
            ),
            markdownBlocks(markdown),
        )
    }

    @Test
    fun `headings, emphasis, strong, links as their text, raw HTML dropped, soft and hard breaks`() {
        val markdown =
            "## Returns\n\nThe *new* **path** of [the docs](https://example.com) <b>here</b>\nnext  \nline\n\n<div>x</div>"
        assertEquals(
            listOf(
                Heading(2, listOf(DocSpan("Returns"))),
                text(
                    DocSpan("The "),
                    DocSpan("new", setOf(EMPHASIS)),
                    DocSpan(" "),
                    DocSpan("path", setOf(STRONG)),
                    DocSpan(" of the docs here next\nline"),
                ),
            ),
            markdownBlocks(markdown),
        )
    }

    @Test
    fun `bullet and numbered lists keep their items, indented code and quotes are blocks`() {
        val markdown = "- one\n- two **2**\n\n3. three\n4. four\n\nend\n\n    indented()\n\n> quoted"
        assertEquals(
            listOf(
                Items(
                    false,
                    1,
                    listOf(listOf(text(DocSpan("one"))), listOf(text(DocSpan("two "), DocSpan("2", setOf(STRONG))))),
                ),
                Items(true, 3, listOf(listOf(text(DocSpan("three"))), listOf(text(DocSpan("four"))))),
                text(DocSpan("end")),
                Code("indented()"),
                Quote(listOf(text(DocSpan("quoted")))),
            ),
            markdownBlocks(markdown),
        )
    }

    @Test
    fun `quotes and lists deeper than the cap flatten into the deepest level, keeping their text`() {
        fun depth(blocks: List<DocBlock>): Int =
            blocks.maxOfOrNull { block ->
                when (block) {
                    is Quote -> 1 + depth(block.blocks)
                    is Items -> 1 + depth(block.items.flatten())
                    else -> 0
                }
            } ?: 0

        fun texts(blocks: List<DocBlock>): List<String> =
            blocks.flatMap { block ->
                when (block) {
                    is Quote -> texts(block.blocks)
                    is Items -> texts(block.items.flatten())
                    is Paragraph -> listOf(block.spans.joinToString("") { it.text })
                    else -> emptyList()
                }
            }

        val quotes = markdownBlocks("> ".repeat(5000) + "deep")
        assertEquals(MAX_NESTING, depth(quotes))
        // commonmark stops nesting at some depth itself and keeps the rest of the markers as text.
        assertTrue(texts(quotes).single().endsWith("deep"))
        val lists = markdownBlocks((0 until 40).joinToString("\n") { "  ".repeat(it) + "- item $it" })
        assertEquals(MAX_NESTING, depth(lists))
        assertEquals((0 until 40).map { "item $it" }, texts(lists))
    }

    @ParameterizedTest
    @CsvSource(
        "plaintext, 'a_b *c*', 'a_b *c*'",
        "markdown, 'a\\_b *c*', 'a_b c'",
    )
    fun `MarkupContent goes by its kind`(
        kind: String,
        value: String,
        shown: String,
    ) {
        val blocks =
            markupBlocks(
                MarkupContent(
                    if (kind ==
                        "markdown"
                    ) {
                        MarkupKind.MARKDOWN
                    } else {
                        MarkupKind.PLAINTEXT
                    },
                    value,
                ),
            )
        assertEquals(shown, (blocks.single() as Paragraph).spans.joinToString("") { it.text })
    }

    @Test
    fun `plain text keeps its lines and splits paragraphs at blank lines, a bare string doc is plain text`() {
        assertEquals(
            listOf(text(DocSpan("first\nsecond")), text(DocSpan("third"))),
            docBlocks(Either.forLeft("first\nsecond\n\n  \nthird\n")),
        )
        assertEquals(emptyList(), docBlocks(null))
    }

    @Test
    fun `old hovers, marked strings with a language are code, bare strings Markdown`() {
        val hover = Hover(listOf(Either.forRight(MarkedString("python", "x: int\n")), Either.forLeft("*doc*")))
        assertEquals(
            listOf(Code("x: int"), text(DocSpan("doc", setOf(EMPHASIS)))),
            hoverBlocks(hover),
        )
        assertEquals(emptyList(), hoverBlocks(null))
    }
}
