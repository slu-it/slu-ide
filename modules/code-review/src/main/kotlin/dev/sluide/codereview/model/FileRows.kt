package dev.sluide.codereview.model

import dev.sluide.fileviewers.DiffKind
import dev.sluide.fileviewers.LineChange

/** Lines of context around each change. */
internal const val CONTEXT_LINES = 3

/** Lines one click on ↑ or ↓ reveals. */
internal const val EXPAND_STEP = 20

/** More changed lines than this start a file collapsed behind Load diff. */
internal const val LOAD_DIFF_LIMIT = 500

/** Lock and generated files, which start collapsed behind Load diff; compared ignoring case. */
private val generatedNames =
    setOf("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "gradle.lockfile", "cargo.lock", "poetry.lock", "go.sum")

private val generatedSuffixes = listOf(".min.js", ".min.css")

/** Whether the file at [path] with [changedLines] starts collapsed behind Load diff. */
internal fun startsCollapsed(
    path: String,
    changedLines: Int,
): Boolean {
    val name = path.substringAfterLast('/').lowercase()
    return changedLines > LOAD_DIFF_LIMIT || name in generatedNames || generatedSuffixes.any { name.endsWith(it) }
}

/** Revealed lines of a gap beyond its context: [fromTop] below its top, [fromBottom] above its bottom. */
internal data class GapExpansion(
    val fromTop: Int = 0,
    val fromBottom: Int = 0,
) {
    /** This expansion after revealing [direction] of a gap of [length] lines. */
    fun expanded(
        direction: ExpandDirection,
        length: Int,
    ): GapExpansion =
        when (direction) {
            ExpandDirection.UP -> copy(fromBottom = minOf(length, fromBottom + EXPAND_STEP))
            ExpandDirection.DOWN -> copy(fromTop = minOf(length, fromTop + EXPAND_STEP))
            ExpandDirection.ALL -> copy(fromTop = length)
        }
}

/** An unchanged run of [length] lines starting at line [original] of the original and [current] of the current. */
internal data class UnchangedRun(
    val original: Int,
    val current: Int,
    val length: Int,
)

/** The unchanged runs around [changes]: one before each change and one after the last. */
internal fun unchangedRuns(
    originalLines: Int,
    currentLines: Int,
    changes: List<LineChange>,
): List<UnchangedRun> {
    var original = 0
    var current = 0
    val runs = ArrayList<UnchangedRun>(changes.size + 1)
    for (change in changes) {
        val length = minOf(change.original.first - original, change.current.first - current).coerceAtLeast(0)
        runs += UnchangedRun(original, current, length)
        original = maxOf(original, change.original.first + change.original.count())
        current = maxOf(current, change.current.first + change.current.count())
    }
    runs += UnchangedRun(original, current, minOf(originalLines - original, currentLines - current).coerceAtLeast(0))
    return runs
}

/**
 * The line and gap rows of [text] (which has changes) in [layout]: [CONTEXT_LINES] of context around each change plus
 * what [expansions] (by gap) reveal, a [ReviewRow.Gap] where lines stay hidden.
 */
internal fun lineRows(
    path: String,
    text: PreparedBody.Text,
    layout: ReviewLayout,
    expansions: Map<Int, GapExpansion>,
): List<ReviewRow> {
    val changes = text.changes
    val runs = unchangedRuns(text.originalLines.size, text.currentLines.size, changes)
    val rows = ArrayList<ReviewRow>()
    val gaps = ArrayList<PendingGap>()

    fun context(
        run: UnchangedRun,
        lines: IntRange,
    ) {
        for (k in lines) {
            rows +=
                if (layout == ReviewLayout.UNIFIED) {
                    ReviewRow.UnifiedLine(path, run.original + k, run.current + k, null)
                } else {
                    ReviewRow.SplitLine(path, run.original + k, run.current + k, null)
                }
        }
    }

    runs.forEachIndexed { gap, run ->
        val last = gap == changes.size
        val expansion = expansions[gap] ?: GapExpansion()
        val top = minOf(run.length, (if (gap == 0) 0 else CONTEXT_LINES) + expansion.fromTop)
        val bottom = minOf(run.length - top, (if (last) 0 else CONTEXT_LINES) + expansion.fromBottom)
        val hidden = run.length - top - bottom
        context(run, 0 until top)
        if (hidden > 0) {
            gaps += PendingGap(rows.size, gap, hidden, last, run.original + top + hidden, run.current + top + hidden)
            rows += ReviewRow.Spacer(path) // replaced by the gap row once the section below is known
        }
        context(run, run.length - bottom until run.length)
        if (!last) rows += changeRows(path, changes[gap], layout)
    }
    gaps.forEachIndexed { index, pending ->
        val end = gaps.getOrNull(index + 1)?.row ?: rows.size
        rows[pending.row] = pending.toRow(path, rows.subList(pending.row + 1, end))
    }
    return rows
}

/** A gap row whose hunk header waits for the rows below it. */
private class PendingGap(
    val row: Int,
    val gap: Int,
    val hidden: Int,
    val last: Boolean,
    /** The first lines below the hidden part. */
    val original: Int,
    val current: Int,
) {
    fun toRow(
        path: String,
        section: List<ReviewRow>,
    ): ReviewRow.Gap {
        val header =
            if (last) {
                ""
            } else {
                val originalCount = section.count { it.originalLine() != null }
                val currentCount = section.count { it.currentLine() != null }
                // A section below a gap starts with context lines, so neither side is empty: no `-a,0` forms.
                "@@ -${original + 1},$originalCount +${current + 1},$currentCount @@"
            }
        return ReviewRow.Gap(path, gap, hidden, up = !last, down = gap > 0, hunkHeader = header)
    }
}

/** The rows of one change: in [ReviewLayout.UNIFIED] its original lines, then its current lines; else paired. */
private fun changeRows(
    path: String,
    change: LineChange,
    layout: ReviewLayout,
): List<ReviewRow> {
    val kind: DiffKind = change.kind
    return if (layout == ReviewLayout.UNIFIED) {
        change.original.map { ReviewRow.UnifiedLine(path, it, null, kind) } +
            change.current.map { ReviewRow.UnifiedLine(path, null, it, kind) }
    } else {
        val originalCount = change.original.count()
        val currentCount = change.current.count()
        List(maxOf(originalCount, currentCount)) { i ->
            ReviewRow.SplitLine(
                path,
                if (i < originalCount) change.original.first + i else null,
                if (i < currentCount) change.current.first + i else null,
                kind,
            )
        }
    }
}

/** The original line of a line row, `null` for other rows and fillers. */
internal fun ReviewRow.originalLine(): Int? =
    when (this) {
        is ReviewRow.UnifiedLine -> original
        is ReviewRow.SplitLine -> original
        else -> null
    }

/** The current line of a line row, `null` for other rows and fillers. */
internal fun ReviewRow.currentLine(): Int? =
    when (this) {
        is ReviewRow.UnifiedLine -> current
        is ReviewRow.SplitLine -> current
        else -> null
    }
