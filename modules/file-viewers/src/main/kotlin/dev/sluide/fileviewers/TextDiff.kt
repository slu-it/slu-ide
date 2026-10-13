package dev.sluide.fileviewers

/** How a file differs from an [original] version of it, e.g. the last commit: the changed blocks of lines. */
data class TextDiff(
    val original: String,
    /** Sorted, not overlapping. */
    val changes: List<LineChange>,
    /** Shown above the original, e.g. `HEAD`. */
    val originalTitle: String = "Original",
    /** Shown above the file. */
    val currentTitle: String = "Current",
)

/**
 * The lines [original] of the original version were replaced by the lines [current] of the file. Both are 0-based;
 * an empty range starts at the line before which lines were added (or after which lines were removed).
 */
data class LineChange(
    val original: IntRange,
    val current: IntRange,
) {
    val kind: DiffKind
        get() =
            when {
                current.isEmpty() -> DiffKind.REMOVED
                original.isEmpty() -> DiffKind.ADDED
                else -> DiffKind.MODIFIED
            }
}

/** What happened to a block of lines; [styleClass] colours it in `syntax.css`. */
enum class DiffKind(
    val styleClass: String,
) {
    /** Only in the original. */
    REMOVED("diff-removed"),

    /** Only in the file. */
    ADDED("diff-added"),

    /** Replaced by different lines. */
    MODIFIED("diff-modified"),
}

/**
 * A row of the side-by-side diff: the [original] line on the left and the [current] line on the right (0-based),
 * either `null` for an empty filler row that keeps both sides aligned, and the [kind] of change, `null` if unchanged.
 */
internal data class DiffRow(
    val original: Int?,
    val current: Int?,
    val kind: DiffKind?,
)

/**
 * Pairs the [originalLines] and [currentLines] row by row: unchanged lines side by side, each change as many rows as
 * its longer side has lines. Ranges beyond the line counts (a diff older than the file) are cut off; lines left over
 * at the end are shown against filler rows.
 */
internal fun alignDiff(
    originalLines: Int,
    currentLines: Int,
    changes: List<LineChange>,
): List<DiffRow> {
    val rows = ArrayList<DiffRow>(maxOf(originalLines, currentLines))
    var original = 0
    var current = 0
    for (change in changes) {
        val originalChanged = change.original.clampTo(originalLines)
        val currentChanged = change.current.clampTo(currentLines)
        while (original < originalChanged.first && current < currentChanged.first) {
            rows += DiffRow(original++, current++, null)
        }
        val kind = change.kind
        val originalCount = originalChanged.count()
        val currentCount = currentChanged.count()
        for (i in 0 until maxOf(originalCount, currentCount)) {
            rows +=
                DiffRow(
                    if (i < originalCount) originalChanged.first + i else null,
                    if (i < currentCount) currentChanged.first + i else null,
                    kind,
                )
        }
        original = maxOf(original, originalChanged.first + originalCount)
        current = maxOf(current, currentChanged.first + currentCount)
    }
    while (original < originalLines || current < currentLines) {
        rows +=
            DiffRow(
                original.takeIf { it < originalLines }?.also { original++ },
                current.takeIf { it < currentLines }?.also { current++ },
                null,
            )
    }
    return rows
}

private fun IntRange.clampTo(lines: Int): IntRange {
    val start = first.coerceIn(0, lines)
    return start until (last + 1).coerceIn(start, lines)
}

/** The lines of [text] without line breaks (`\n` or `\r\n`); a final line break does not start another line. */
internal fun textLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split('\n').map { it.removeSuffix("\r") }
    return if (text.endsWith('\n')) lines.dropLast(1) else lines
}
