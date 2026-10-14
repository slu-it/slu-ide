package dev.sluide.fileviewers

/** How a line of a file differs from the original, shown beside its line number; [styleClass] colours it. */
internal enum class LineMarker(
    val styleClass: String,
) {
    /** The line is new. */
    ADDED("diff-added"),

    /** The line replaces different lines. */
    MODIFIED("diff-modified"),

    /** Lines of the original were removed right above this line (or below it, if it is the last line). */
    REMOVED_ABOVE("diff-removed-above"),
}

/** The [LineMarker]s of the lines of a file, `null` for unchanged lines. */
internal object LineMarkers {
    /**
     * The marker of each of the [lineCount] lines of a file with the [changes]: added and modified lines as such, and
     * removed lines on the line after them, or the last line if they were at the end. Changes beyond [lineCount] are
     * cut off.
     */
    fun of(
        changes: List<LineChange>,
        lineCount: Int,
    ): List<LineMarker?> {
        val markers = arrayOfNulls<LineMarker>(lineCount)
        for (change in changes) {
            val kind = change.kind
            if (kind == DiffKind.REMOVED) {
                markRemoved(markers, change.current.first)
            } else {
                val marker = if (kind == DiffKind.ADDED) LineMarker.ADDED else LineMarker.MODIFIED
                for (line in change.current.clampTo(lineCount)) markers[line] = marker
            }
        }
        return markers.asList()
    }

    /** Marks lines removed before [line] on that line, or on the last line at the end, unless it has a marker. */
    private fun markRemoved(
        markers: Array<LineMarker?>,
        line: Int,
    ) {
        val marked = line.coerceAtMost(markers.lastIndex)
        if (marked >= 0 && markers[marked] == null) markers[marked] = LineMarker.REMOVED_ABOVE
    }
}
