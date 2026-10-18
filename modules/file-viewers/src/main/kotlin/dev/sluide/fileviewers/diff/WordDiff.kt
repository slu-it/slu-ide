package dev.sluide.fileviewers.diff

import dev.sluide.fileviewers.DiffKind
import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.clampTo

/** Style class of the changed words within modified lines, coloured in `syntax.css`. */
internal const val CHANGED_WORDS_STYLE = "diff-changed-words"

/**
 * The changed words of modified lines (ADR 0019): for each side, a line index (0-based) to the column ranges of its
 * changed words. Lines without changed words are absent.
 */
internal data class WordChanges(
    val original: Map<Int, List<IntRange>>,
    val current: Map<Int, List<IntRange>>,
)

/** A word, a run of whitespace, a line break, or any other single character. */
private val token = Regex("""[\p{L}\p{N}_]+|[^\S\n]+|\n|.""")

/**
 * Finds the changed words of each modified block of [changes]: the block's lines on both sides are compared word by
 * word as a whole, so that a line inserted into the block does not shift the comparison of the lines after it. Ranges
 * are cut off like in [dev.sluide.fileviewers.alignDiff]. A block whose comparison would take more than [maxCells]
 * steps is left out.
 */
internal fun wordChanges(
    originalLines: List<String>,
    currentLines: List<String>,
    changes: List<LineChange>,
    maxCells: Int = 1_000_000,
): WordChanges {
    val original = HashMap<Int, MutableList<IntRange>>()
    val current = HashMap<Int, MutableList<IntRange>>()
    for (change in changes) {
        if (change.kind != DiffKind.MODIFIED) continue
        val originalBlock = Block(originalLines, change.original.clampTo(originalLines.size))
        val currentBlock = Block(currentLines, change.current.clampTo(currentLines.size))
        val (originalChanged, currentChanged) =
            changedTokens(originalBlock.tokens, currentBlock.tokens, maxCells) ?: continue
        originalBlock.addRanges(originalChanged, original)
        currentBlock.addRanges(currentChanged, current)
    }
    return WordChanges(original, current)
}

/** The [lines] of one side of a change, split into tokens. */
private class Block(
    lines: List<String>,
    private val range: IntRange,
) {
    private val text = range.joinToString("\n") { lines[it] }
    private val matches = token.findAll(text).toList()
    val tokens = matches.map { it.value }

    /** Adds the column ranges of the [changed] tokens to [ranges], adjacent ones merged, line breaks left out. */
    fun addRanges(
        changed: BooleanArray,
        ranges: MutableMap<Int, MutableList<IntRange>>,
    ) {
        var line = range.first
        var lineStart = 0
        matches.forEachIndexed { index, match ->
            if (match.value == "\n") {
                line++
                lineStart = match.range.last + 1
                return@forEachIndexed
            }
            if (!changed[index]) return@forEachIndexed
            val columns = (match.range.first - lineStart)..(match.range.last - lineStart)
            val lineRanges = ranges.getOrPut(line) { mutableListOf() }
            val previous = lineRanges.lastOrNull()
            if (previous != null && previous.last + 1 == columns.first) {
                lineRanges[lineRanges.lastIndex] = previous.first..columns.last
            } else {
                lineRanges += columns
            }
        }
    }
}

/**
 * Marks the tokens of [a] and [b] that are not part of their longest common subsequence, or gives `null` if finding it
 * would take more than [maxCells] steps. A common prefix is matched first. Of equally long subsequences, the one that
 * matches tokens earliest is taken, so that the end of a changed line is not matched with the end of a line added
 * after it.
 */
private fun changedTokens(
    a: List<String>,
    b: List<String>,
    maxCells: Int,
): Pair<BooleanArray, BooleanArray>? {
    var start = 0
    while (start < a.size && start < b.size && a[start] == b[start]) start++
    val n = a.size - start
    val m = b.size - start
    if (n.toLong() * m > maxCells) return null
    val aChanged = BooleanArray(a.size)
    val bChanged = BooleanArray(b.size)
    // lcs[i * (m + 1) + j]: the length of the longest common subsequence of a from start + i and b from start + j
    val lcs = IntArray((n + 1) * (m + 1))
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i * (m + 1) + j] =
                if (a[start + i] == b[start + j]) {
                    lcs[(i + 1) * (m + 1) + j + 1] + 1
                } else {
                    maxOf(lcs[(i + 1) * (m + 1) + j], lcs[i * (m + 1) + j + 1])
                }
        }
    }
    var i = 0
    var j = 0
    while (i < n || j < m) {
        when {
            i < n && j < m && a[start + i] == b[start + j] -> {
                i++
                j++
            }

            j == m || (i < n && lcs[(i + 1) * (m + 1) + j] >= lcs[i * (m + 1) + j + 1]) -> {
                aChanged[start + i++] = true
            }

            else -> {
                bChanged[start + j++] = true
            }
        }
    }
    return aChanged to bChanged
}
