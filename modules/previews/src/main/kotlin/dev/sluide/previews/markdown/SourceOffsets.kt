package dev.sluide.previews.markdown

import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.Code
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.jsoup.parser.Parser

/**
 * A part of a rendered text that maps to the source one to one from [offset], the source offset of its first char,
 * starting at [start] of the rendered text.
 */
internal data class TextRun(
    val start: Int,
    val offset: Int,
)

/**
 * The runs of [literal], the rendered text of [source] (the source it was parsed from, at offset [from]): a new run
 * starts where a backslash escape or an entity of the source makes the two differ, so that the chars after it keep
 * their source offsets. An entity is a run of its own, rendered shorter or longer than written. Source the literal
 * leaves out before its start (a task item's `[ ] `) is skipped. Where the two differ in another way (a line ending
 * in a code span), the rest stays in the run before. At least one run, starting at 0.
 */
internal fun textRuns(
    literal: String,
    source: String,
    from: Int,
): List<TextRun> {
    val runs = mutableListOf<TextRun>()
    var at = 0
    var position = 0
    // The source position the next char continues the current run at; -1 after an entity, which ends its run.
    var next = -1

    fun take(sourceAt: Int) {
        if (sourceAt != next) runs += TextRun(at, from + sourceAt)
    }

    while (at < literal.length && position < source.length) {
        val char = literal[at]
        val entity = if (source[position] == '&') ENTITY.matchAt(source, position)?.value else null
        val decoded = entity?.let { Parser.unescapeEntities(it, false) }
        when {
            // An entity in a code span stays as written.
            source[position] == char && (decoded == null || decoded == entity || literal.startsWith(entity, at)) -> {
                take(position)
                at++
                position++
                next = position
            }

            source[position] == '\\' && position + 1 < source.length && source[position + 1] == char -> {
                take(position + 1)
                at++
                position += 2
                next = position
            }

            entity != null && decoded != null && decoded != entity && literal.startsWith(decoded, at) -> {
                runs += TextRun(at, from + position)
                at += decoded.length
                position += entity.length
                next = -1
            }

            else -> {
                // Before the first run, the literal may start later in the source: find its plain start there.
                val found = if (runs.isEmpty()) source.indexOf(plainStart(literal), position + 1) else -1
                if (found < 0) break
                position = found
            }
        }
    }
    return runs.ifEmpty { listOf(TextRun(0, from)) }
}

/** The start of [literal] up to its first char that is neither a letter, a digit nor a space; at least one char. */
private fun plainStart(literal: String): String =
    literal.take(literal.takeWhile { it.isLetterOrDigit() || it == ' ' }.length.coerceAtLeast(1))

/** The box of a task item before its text in the source, as the task list extension reads it. */
private val TASK_BOX = Regex("""^\[[xX\s]]\s+""")

/** An HTML entity as CommonMark reads it: named, decimal or hexadecimal. */
private val ENTITY = Regex("&(?:#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});")

/**
 * Renders texts and inline code as `<span>` runs ([textRuns]) whose attribute [attribute] is N, the offset in
 * [markdown] (the source as parsed: with its front matter kept as spaces) of the run's first char, for the text marks
 * of the preview (`TextMarks`). [attribute] is a name of this rendering only, which the file's own HTML cannot carry;
 * the sanitiser turns it into `data-o` after dropping the file's `data-o` attributes. Texts in fenced code, math and
 * diagrams are no such nodes and stay as they are; a node without a source span is written as plain text.
 */
internal class SourceOffsetRenderer(
    private val context: HtmlNodeRendererContext,
    private val markdown: String,
    private val attribute: String,
) : NodeRenderer {
    private val html = context.writer

    override fun getNodeTypes(): Set<Class<out Node>> = setOf(Text::class.java, Code::class.java)

    override fun render(node: Node) {
        when (node) {
            is Text -> {
                write(node.literal, node, taskBoxLength(node))
            }

            is Code -> {
                html.tag("code", context.extendAttributes(node, "code", emptyMap()))
                write(node.literal, node, codeStart(node.literal, source(node).orEmpty()))
                html.tag("/code")
            }
        }
    }

    /** Writes [literal], the text of [node], whose first char is at [skip] in its source. */
    private fun write(
        literal: String,
        node: Node,
        skip: Int,
    ) {
        val source = source(node)
        val start = node.sourceSpans.firstOrNull()?.inputIndex
        if (literal.isEmpty() || source == null || start == null) {
            html.text(literal)
            return
        }
        val runs = textRuns(literal, source.substring(skip.coerceAtMost(source.length)), start + skip)
        runs.forEachIndexed { index, run ->
            val end = runs.getOrNull(index + 1)?.start ?: literal.length
            html.tag("span", mapOf(attribute to run.offset.toString()))
            html.text(literal.substring(run.start, end))
            html.tag("/span")
        }
    }

    /** The source [node] was parsed from, `null` without source spans. */
    private fun source(node: Node): String? {
        val spans = node.sourceSpans
        if (spans.isEmpty()) return null
        val start = spans.first().inputIndex.coerceIn(0, markdown.length)
        val end = (spans.last().inputIndex + spans.last().length).coerceIn(start, markdown.length)
        return markdown.substring(start, end)
    }

    /**
     * The length of the task box (`[ ] `, `[x] `) before [text] in its source, if it is the first text of a task
     * item's first paragraph (right after the marker), whose literal the task list extension cut after the box; 0
     * otherwise.
     */
    private fun taskBoxLength(text: Text): Int {
        val paragraph = text.parent as? Paragraph ?: return 0
        if (paragraph.firstChild !== text || paragraph.previous !is TaskListItemMarker) return 0
        return TASK_BOX.find(source(text).orEmpty())?.value?.length ?: 0
    }

    /** Where the [literal] of a code span starts in its [source]: after the backticks and a space stripped. */
    private fun codeStart(
        literal: String,
        source: String,
    ): Int {
        val ticks = source.takeWhile { it == '`' }.length
        return if (!source.startsWith(literal, ticks) && source.startsWith(literal, ticks + 1)) ticks + 1 else ticks
    }
}
