package dev.sluide.previews.yaml

import org.yaml.snakeyaml.DumperOptions.ScalarStyle
import org.yaml.snakeyaml.error.Mark

/** A node's properties as written and the offset where its own text (for a scalar) starts. */
internal class WrittenProperties(
    val properties: List<String>,
    val valueStart: Int,
)

/**
 * A scalar's [lines] as [YamlSource.scalarLines] gives them and the [breaks] between them as written (`\n`, `\r\n`,
 * `\r`, NEL, LS or PS; one fewer than lines, or as many where a `|+` block scalar's value ends with an LS or PS
 * after its last line).
 */
internal class ScalarLines(
    val lines: List<String>,
    val breaks: List<String>,
)

/**
 * The YAML source text as SnakeYAML's marks point into it: marks count code points, so [offset] turns them into char
 * offsets; lines are counted as the editor does (`\n`, `\r\n` and a lone `\r` each end one).
 */
internal class YamlSource(
    private val text: String,
) {
    private val lineBreak = Regex("\r\n|\r|\n")

    /** What SnakeYAML takes for a line break in a scalar. */
    private val scalarBreak = Regex("\r\n|[\r\n\u0085\u2028\u2029]")

    private val lineStarts: IntArray =
        buildList {
            add(0)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\n' || (c == '\r' && text.getOrNull(i + 1) != '\n')) add(i + 1)
                i++
            }
        }.toIntArray()

    /** Without surrogate pairs, code point indices are char offsets. */
    private val simple = text.none { it.isSurrogate() }

    // The last conversion: marks mostly come in source order, so the next one starts from here.
    private var lastIndex = 0
    private var lastOffset = 0

    /** The char offset of the code point index [index] (as in a [Mark]), clamped to the text. */
    fun offsetOfCodePoint(index: Int): Int {
        if (simple) return index.coerceIn(0, text.length)
        val offset =
            try {
                text.offsetByCodePoints(lastOffset, index - lastIndex)
            } catch (_: IndexOutOfBoundsException) {
                return if (index <= 0) 0 else text.length
            }
        lastIndex = index
        lastOffset = offset
        return offset
    }

    fun offset(mark: Mark): Int = offsetOfCodePoint(mark.index)

    /** The 0-based line of the char [offset]. */
    fun line(offset: Int): Int {
        val found = lineStarts.binarySearch(offset)
        return if (found >= 0) found else -found - 2
    }

    /** "Line 3, column 7: " for [mark] (1-based, the column in chars). */
    fun where(mark: Mark): String = whereOffset(offset(mark))

    fun whereCodePoint(index: Int): String = whereOffset(offsetOfCodePoint(index))

    private fun whereOffset(offset: Int): String {
        val line = line(offset)
        return "Line ${line + 1}, column ${offset - lineStarts[line] + 1}: "
    }

    /** The directive lines (`%YAML 1.2`, `%TAG ! tag:x:`) between [start] and [end], without comments. */
    fun directives(
        start: Int,
        end: Int,
    ): List<String> =
        text
            .substring(start, maxOf(start, end))
            .split(lineBreak)
            .map { it.trim() }
            .filter { it.startsWith('%') }
            .map { it.replace(Regex("""[ \t]#.*$"""), "").trimEnd() }

    /**
     * The anchor and tag of the node that starts at [start] (at its first property, if it has any) as written, and
     * where its own text starts: after them, the space, line breaks and comments between them skipped. [anchor] and
     * [tag] are SnakeYAML's (null if the node has none); a tag that cannot be found is written as resolved.
     */
    fun properties(
        start: Int,
        end: Int,
        anchor: String?,
        tag: String?,
    ): WrittenProperties {
        if (anchor == null && tag == null) return WrittenProperties(emptyList(), start)
        val properties = ArrayList<String>(2)
        val writtenAnchor = anchor?.let { "&$it" }
        var anchorFound = writtenAnchor == null
        var tagFound = tag == null
        var i = start
        repeat(2) {
            i = skipSpace(i, end)
            if (writtenAnchor != null && !anchorFound && text.startsWith(writtenAnchor, i)) {
                properties += writtenAnchor
                i += writtenAnchor.length
                anchorFound = true
            } else if (!tagFound && text.getOrNull(i) == '!') {
                val tokenEnd =
                    if (text.startsWith("!<", i)) {
                        text.indexOf('>', i).let { if (it in i until end) it + 1 else end }
                    } else {
                        (i until end).firstOrNull { text[it].isWhitespace() } ?: end
                    }
                properties += text.substring(i, tokenEnd)
                i = tokenEnd
                tagFound = true
            }
        }
        if (writtenAnchor != null && !anchorFound) properties.add(0, writtenAnchor)
        if (!tagFound && tag != null) properties += shortTag(tag)
        return WrittenProperties(properties, skipSpace(i, end))
    }

    /** [tag] in its usual short form, for a tag not found in the source. */
    private fun shortTag(tag: String) =
        when {
            tag.startsWith(CORE_PREFIX) -> "!!" + tag.removePrefix(CORE_PREFIX)
            tag.startsWith("!") -> tag
            else -> "!<$tag>"
        }

    /** The offset after the spaces, line breaks and comments from [from], at most [end]. */
    private fun skipSpace(
        from: Int,
        end: Int,
    ): Int {
        var i = from
        while (i < end) {
            when (text[i]) {
                ' ', '\t', '\r', '\n' -> i++
                '#' -> while (i < end && text[i] != '\n' && text[i] != '\r') i++
                else -> return i
            }
        }
        return minOf(i, end)
    }

    /**
     * The lines of the scalar written from [start] to [end] in [style] that reads as [value], ready to be indented
     * anew: the first line as written; following lines of a plain or quoted scalar without their leading space (which
     * is not part of its value), lines of a block scalar (`|`, `>`) without the block's indentation. Lines end where
     * SnakeYAML sees a line break, also at NEL, LS and PS (which the editor does not count). A block scalar's first
     * line is its header without a comment, an explicit indentation indicator rewritten to 2 (the step of the formatted
     * text, whose block lines are 2 in from their parent) and `-` as its chomping where its non-empty value has no final
     * line break (it ended the file); its trailing empty lines are kept only where they are part of the value (`|+`, `>+`).
     * Lines of a block scalar with no more than the block's indentation, and whitespace-only lines of other scalars,
     * become empty.
     */
    fun scalarLines(
        start: Int,
        end: Int,
        style: ScalarStyle,
        value: String,
    ): ScalarLines {
        val written = text.substring(minOf(start, end), end)
        val lines = written.split(scalarBreak)
        val breaks = scalarBreak.findAll(written).map { it.value }.toList()
        if (style == ScalarStyle.LITERAL || style == ScalarStyle.FOLDED) return blockLines(lines, breaks, value)
        return ScalarLines(listOf(lines[0]) + lines.drop(1).map { it.trimStart(' ', '\t') }, breaks)
    }

    private fun blockLines(
        lines: List<String>,
        breaks: List<String>,
        value: String,
    ): ScalarLines {
        val header = lines[0]
        val written = header.drop(1).takeWhile { it in "+-123456789" }.take(2)
        // A value without a final line break ended the file; the formatted text ends with one, which must not count.
        val indicators =
            when {
                value.isEmpty() || value.last() in "\n\u2028\u2029" || '-' in written -> written
                '+' in written -> written.replace('+', '-')
                else -> "$written-"
            }
        val keep = '+' in indicators
        val content = lines.drop(1)
        val indent = blockIndent(content, value)
        // The scanner ends a block scalar after the indentation of the line that follows it: that rest is no line.
        val last = content.lastOrNull()
        val complete =
            if (last != null && last.isBlank() && last.length <= indent) content.dropLast(1) else content
        val body =
            complete.map {
                when {
                    it.isBlank() && it.length <= indent -> ""
                    it.length >= indent -> it.substring(indent)
                    else -> it.trimStart()
                }
            }
        val trimmed = if (keep) body else body.dropLastWhile { it.isEmpty() }
        val first = header.take(1) + indicators.map { if (it.isDigit()) '2' else it }.joinToString("")
        // An LS or PS after the last line is part of the value. Clipped, an empty line after it keeps it the final
        // break; kept (`|+`), where the empty line's own break would count too, it ends the line instead.
        if (breaks.getOrNull(trimmed.size) in separators) {
            return if (keep) {
                ScalarLines(listOf(first) + trimmed, breaks.take(trimmed.size + 1))
            } else {
                ScalarLines(listOf(first) + trimmed + "", breaks.take(trimmed.size + 1))
            }
        }
        return ScalarLines(listOf(first) + trimmed, breaks.take(trimmed.size))
    }

    /**
     * The indentation of the block scalar whose source lines after the header are [content] and whose [value] is
     * known: the first line with text keeps in the value what it has beyond the block's indentation. Without such a
     * line, its own indentation.
     */
    private fun blockIndent(
        content: List<String>,
        value: String,
    ): Int {
        val first = content.indexOfFirst { line -> line.any { it != ' ' } }
        if (first < 0) return content.maxOfOrNull { it.length } ?: 0
        val written = content[first].takeWhile { it == ' ' }.length
        val kept =
            value
                .split('\n', '\u2028', '\u2029')
                .getOrNull(first)
                ?.takeWhile { it == ' ' }
                ?.length ?: 0
        return if (kept in 0..written) written - kept else written
    }

    private companion object {
        const val CORE_PREFIX = "tag:yaml.org,2002:"

        /** The line and paragraph separators, line breaks to SnakeYAML that it keeps in values as they are. */
        val separators = setOf("\u2028", "\u2029")
    }
}
