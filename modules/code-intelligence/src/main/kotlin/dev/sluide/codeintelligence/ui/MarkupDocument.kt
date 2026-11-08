package dev.sluide.codeintelligence.ui

import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListItem
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.commonmark.node.Node as MdNode

/** How a run of text in a [DocBlock] is set. */
internal enum class SpanStyle { STRONG, EMPHASIS, CODE }

/** A run of text with its [styles]. */
internal data class DocSpan(
    val text: String,
    val styles: Set<SpanStyle> = emptySet(),
)

/**
 * The documentation a language server sends (hover, completion and signature docs; LSP `MarkupContent`) as the blocks
 * the popups show (ADR 0147): what is left of Markdown once links are their text, raw HTML is dropped and entities and
 * backslash escapes are decoded.
 */
internal sealed interface DocBlock {
    data class Paragraph(
        val spans: List<DocSpan>,
    ) : DocBlock

    data class Heading(
        val level: Int,
        val spans: List<DocSpan>,
    ) : DocBlock

    /** A fenced or indented code block, or plain text that keeps its lines. */
    data class Code(
        val text: String,
    ) : DocBlock

    /** A bullet list, or a numbered one counting from [start]; each item is a list of blocks. */
    data class Items(
        val ordered: Boolean,
        val start: Int,
        val items: List<List<DocBlock>>,
    ) : DocBlock

    data class Quote(
        val blocks: List<DocBlock>,
    ) : DocBlock

    data object Rule : DocBlock
}

private val parser: Parser = Parser.builder().build()

/** [markdown] as blocks. */
internal fun markdownBlocks(markdown: String): List<DocBlock> = blocksOf(parser.parse(markdown), 0)

/**
 * How deep quotes and lists nest at most; deeper ones are flattened into the blocks of the deepest, so that hostile
 * docs (thousands of `>`) neither recurse nor nest that deep.
 */
internal const val MAX_NESTING = 8

/** [text] (plain text) as paragraphs, one per run of lines between blank lines, keeping its line breaks. */
internal fun plainTextBlocks(text: String): List<DocBlock> =
    text
        .replace("\r\n", "\n")
        .split(Regex("\n([ \t]*\n)+"))
        .map { it.trim('\n') }
        .filter { it.isNotBlank() }
        .map { DocBlock.Paragraph(listOf(DocSpan(it))) }

/** [content] by its kind: Markdown, or anything else as plain text; nothing for `null`. */
internal fun markupBlocks(content: MarkupContent?): List<DocBlock> {
    val value = content?.value ?: return emptyList()
    return if (content.kind == MarkupKind.MARKDOWN) markdownBlocks(value) else plainTextBlocks(value)
}

/** Documentation of a completion item, signature or parameter: a bare string is plain text. */
internal fun docBlocks(doc: Either<String, MarkupContent>?): List<DocBlock> =
    when {
        doc == null -> emptyList()
        doc.isLeft -> plainTextBlocks(doc.left.orEmpty())
        else -> markupBlocks(doc.right)
    }

/**
 * The contents of a hover answer: `MarkupContent`, or the deprecated marked strings (a bare string is Markdown, one
 * with a language is code).
 */
internal fun hoverBlocks(hover: Hover?): List<DocBlock> {
    val contents = hover?.contents ?: return emptyList()
    if (contents.isRight) return markupBlocks(contents.right)
    return contents.left.orEmpty().flatMap { part ->
        when {
            part == null -> {
                emptyList()
            }

            part.isLeft -> {
                markdownBlocks(part.left.orEmpty())
            }

            else -> {
                part.right
                    ?.value
                    ?.let { listOf(DocBlock.Code(it.trimEnd('\n'))) }
                    .orEmpty()
            }
        }
    }
}

private fun blocksOf(
    parent: MdNode,
    depth: Int,
): List<DocBlock> = children(parent).flatMap { blockOf(it, depth) }

private fun blockOf(
    node: MdNode,
    depth: Int,
): List<DocBlock> =
    when (node) {
        is Paragraph -> {
            spansOf(node)
                .takeIf { spans ->
                    spans.any { it.text.isNotBlank() }
                }?.let { listOf(DocBlock.Paragraph(it)) }
                .orEmpty()
        }

        is Heading -> {
            listOf(DocBlock.Heading(node.level, spansOf(node)))
        }

        is FencedCodeBlock -> {
            listOf(DocBlock.Code(node.literal.orEmpty().trimEnd('\n')))
        }

        is IndentedCodeBlock -> {
            listOf(DocBlock.Code(node.literal.orEmpty().trimEnd('\n')))
        }

        is BulletList, is OrderedList, is BlockQuote -> {
            if (depth >= MAX_NESTING) flattened(node) else listOf(nested(node, depth + 1))
        }

        is ThematicBreak -> {
            listOf(DocBlock.Rule)
        }

        is HtmlBlock, is LinkReferenceDefinition -> {
            emptyList()
        }

        else -> {
            blocksOf(node, depth)
        }
    }

/** A list or quote one level deeper. */
private fun nested(
    node: MdNode,
    depth: Int,
): DocBlock {
    fun items() = children(node).map { item -> if (item is ListItem) blocksOf(item, depth) else blockOf(item, depth) }
    return when (node) {
        is OrderedList -> DocBlock.Items(true, node.markerStartNumber ?: 1, items())
        is BulletList -> DocBlock.Items(false, 1, items())
        else -> DocBlock.Quote(blocksOf(node, depth))
    }
}

/** The leaf blocks inside [container] in order, without its lists and quotes; walked without recursion. */
private fun flattened(container: MdNode): List<DocBlock> {
    val blocks = mutableListOf<DocBlock>()
    val pending = ArrayDeque(children(container))
    while (pending.isNotEmpty()) {
        val node = pending.removeFirst()
        when (node) {
            is BulletList, is OrderedList, is BlockQuote, is ListItem -> {
                children(
                    node,
                ).asReversed().forEach(pending::addFirst)
            }

            else -> {
                blocks += blockOf(node, MAX_NESTING)
            }
        }
    }
    return blocks
}

/** The inline content of [parent] as spans, adjacent ones of the same style merged. */
private fun spansOf(parent: MdNode): List<DocSpan> {
    val spans = mutableListOf<DocSpan>()

    fun add(
        text: String,
        styles: Set<SpanStyle>,
    ) {
        if (text.isEmpty()) return
        val last = spans.lastOrNull()
        if (last != null && last.styles == styles) {
            spans[spans.lastIndex] = last.copy(text = last.text + text)
        } else {
            spans += DocSpan(text, styles)
        }
    }

    fun collect(
        node: MdNode,
        styles: Set<SpanStyle>,
    ) {
        when (node) {
            is Text -> add(node.literal.orEmpty(), styles)

            is Code -> add(node.literal.orEmpty(), styles + SpanStyle.CODE)

            is Emphasis -> children(node).forEach { collect(it, styles + SpanStyle.EMPHASIS) }

            is StrongEmphasis -> children(node).forEach { collect(it, styles + SpanStyle.STRONG) }

            is SoftLineBreak -> add(" ", styles)

            is HardLineBreak -> add("\n", styles)

            is HtmlInline -> Unit

            // Links and images show their text; anything else its inline children.
            else -> children(node).forEach { collect(it, styles) }
        }
    }
    children(parent).forEach { collect(it, emptySet()) }
    return spans
}

private fun children(parent: MdNode): List<MdNode> = generateSequence(parent.firstChild) { it.next }.toList()
