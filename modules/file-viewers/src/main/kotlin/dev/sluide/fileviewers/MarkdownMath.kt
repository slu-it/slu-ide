package dev.sluide.fileviewers

import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.parser.SourceLine
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.block.AbstractBlockParser
import org.commonmark.parser.block.AbstractBlockParserFactory
import org.commonmark.parser.block.BlockContinue
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer

/** TeX math in Markdown, `$…$` inline or `$$…$$` on its own; [display] math is set as its own block. */
internal class MathInline(
    val tex: String,
    val display: Boolean,
) : CustomNode()

/** TeX math between lines of `$$`. */
internal class MathBlock : CustomBlock() {
    var tex = ""
}

/**
 * Math in Markdown, as GitHub and pandoc read it, for KaTeX in the preview (ADR 0017):
 * - `$…$` inline: no space after the opening `$` or before the closing one, and no digit right after it, so that
 *   `$5 and $6` stays text; `\$` is a dollar sign;
 * - `$$…$$` inline, and blocks between lines of `$$`, as display math;
 * - fenced ` ```math ` blocks are display math too (see `MarkdownRenderer`).
 *
 * Math renders as `<span class="math">` or `<div class="math display">` with the TeX as text; the page's script
 * typesets it.
 */
internal object MarkdownMath : Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {
    override fun extend(builder: Parser.Builder) {
        builder.customInlineContentParserFactory(InlineMathFactory)
        builder.customBlockParserFactory(MathBlockParserFactory)
    }

    override fun extend(builder: HtmlRenderer.Builder) {
        builder.nodeRendererFactory(::MathRenderer)
    }

    private object InlineMathFactory : InlineContentParserFactory {
        override fun getTriggerCharacters() = setOf('$')

        override fun create() = InlineMathParser
    }

    private object InlineMathParser : InlineContentParser {
        // commonmark's "no inline" is null.
        override fun tryParse(state: InlineParserState): ParsedInline? {
            val scanner = state.scanner()
            scanner.next()
            val display = scanner.next('$')
            val contentStart = scanner.position()
            if (!scanner.hasNext() || (!display && scanner.peek().isWhitespace())) return ParsedInline.none()
            while (scanner.find('$') >= 0) {
                val contentEnd = scanner.position()
                val before = scanner.peekPreviousCodePoint()
                if (before == '\\'.code) {
                    scanner.next()
                    continue
                }
                if (display) {
                    if (scanner.next("$$")) {
                        val tex = scanner.getSource(contentStart, contentEnd).content
                        return if (tex.isBlank()) {
                            ParsedInline.none()
                        } else {
                            ParsedInline.of(
                                MathInline(tex, true),
                                scanner.position(),
                            )
                        }
                    }
                    scanner.next()
                    continue
                }
                scanner.next()
                if (Character.isWhitespace(before) || scanner.peek().isDigit()) continue
                val tex = scanner.getSource(contentStart, contentEnd).content
                return ParsedInline.of(MathInline(tex, false), scanner.position())
            }
            return ParsedInline.none()
        }
    }

    private object MathBlockParserFactory : AbstractBlockParserFactory() {
        override fun tryStart(
            state: ParserState,
            matchedBlockParser: MatchedBlockParser,
            // commonmark's "no block start" is null.
        ): BlockStart? {
            val line = state.line.content
            if (state.indent >= 4 || line.substring(state.nextNonSpaceIndex).trim() != "$$") return BlockStart.none()
            return BlockStart.of(MathBlockParser()).atIndex(line.length)
        }
    }

    private class MathBlockParser : AbstractBlockParser() {
        private val block = MathBlock()
        private val lines = mutableListOf<String>()
        private var started = false

        override fun getBlock() = block

        override fun tryContinue(state: ParserState): BlockContinue {
            val line = state.line.content
            return if (line.substring(state.nextNonSpaceIndex).trim() == "$$") {
                BlockContinue.finished()
            } else {
                BlockContinue.atIndex(state.index)
            }
        }

        override fun addLine(line: SourceLine) {
            // The first call is the rest of the opening `$$` line, which is empty.
            if (started) lines += line.content.toString() else started = true
        }

        override fun closeBlock() {
            block.tex = lines.joinToString("\n")
        }
    }

    private class MathRenderer(
        private val context: HtmlNodeRendererContext,
    ) : NodeRenderer {
        private val html = context.writer

        override fun getNodeTypes(): Set<Class<out Node>> = setOf(MathInline::class.java, MathBlock::class.java)

        override fun render(node: Node) {
            when (node) {
                is MathInline -> {
                    val tag = if (node.display) "div" else "span"
                    val style = if (node.display) "math display" else "math"
                    html.tag(tag, context.extendAttributes(node, tag, mapOf("class" to style)))
                    html.text(node.tex)
                    html.tag("/$tag")
                }

                is MathBlock -> {
                    display(node, node.tex)
                }
            }
        }

        private fun display(
            node: Node,
            tex: String,
        ) {
            html.line()
            html.tag("div", context.extendAttributes(node, "div", mapOf("class" to "math display")))
            html.text(tex)
            html.tag("/div")
            html.line()
        }
    }
}
