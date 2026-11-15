package dev.sluide.fileexplorer.search

/**
 * The explorer search's match rule (case-insensitive, no regex). Text without '/' matches when the entry's name
 * contains it. Text with '/' is searched in the path relative to the root with a '/' put in front, so a leading '/'
 * means "a name starting with" at any level (`/build` finds `build` and `x/build`, not `mybuild`). It matches when
 * some occurrence ends inside the entry's name: `main/App` finds `src/main/App.kt`, while `src/main` finds the
 * folder `src/main` but not the files in it. The text is not trimmed.
 */
internal class NameQuery(
    private val text: String,
) {
    /** Whether the text contains '/': then it matches against the path relative to the root. */
    val usesPath: Boolean = '/' in text

    /**
     * Whether the entry matches. [relativePath] is the entry's path relative to its root, '/'-separated, without
     * leading '/'; [name] its file name.
     */
    fun matches(
        relativePath: String,
        name: String,
    ): Boolean {
        if (!usesPath) return name.contains(text, ignoreCase = true)
        val path = "/$relativePath"
        val last = path.lastIndexOf(text, ignoreCase = true)
        return last >= 0 && last + text.length > path.length - name.length
    }

    /**
     * The range of [shown] (the row's display text; may be a compacted "src/main/kotlin" chain, or a root label) to
     * highlight, or null. [relativePath] is the path of the row's last folder/entry relative to its root. A path
     * query highlights the first occurrence that ends after the start of [shown] and inside a name, clipped to
     * [shown]; a hit in a middle folder of a chain counts.
     */
    fun highlight(
        shown: String,
        relativePath: String,
    ): IntRange? {
        if (relativePath.isEmpty() || !relativePath.endsWith(shown)) return null
        if (!usesPath) {
            val start = shown.indexOf(text, ignoreCase = true)
            return if (start < 0) null else start until start + text.length
        }
        val path = "/$relativePath"
        val offset = path.length - shown.length
        var from = path.indexOf(text, ignoreCase = true)
        while (from >= 0) {
            val end = from + text.length
            if (end > offset && path[end - 1] != '/') return clipped(from - offset, end - offset, shown.length)
            from = path.indexOf(text, from + 1, ignoreCase = true)
        }
        return null
    }

    private fun clipped(
        start: Int,
        end: Int,
        length: Int,
    ): IntRange? {
        val from = maxOf(start, 0)
        val to = minOf(end, length)
        return if (from < to) from until to else null
    }
}
