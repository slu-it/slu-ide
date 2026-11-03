package dev.sluide.databases.ui.console

/** What the history popup shows and where a chosen statement goes; no toolkit. */
internal object HistoryFilter {
    const val MAX_LABEL = 100

    /** A text change: [start, end) is replaced by [text]. */
    data class Edit(
        val start: Int,
        val end: Int,
        val text: String,
    )

    /** The entries having every space-separated word of [query] (ignoring case); all of them for a blank query. */
    fun filter(
        entries: List<String>,
        query: String,
    ): List<String> {
        val words = query.split(' ', '\t').filter { it.isNotEmpty() }
        if (words.isEmpty()) return entries
        return entries.filter { entry -> words.all { entry.contains(it, ignoreCase = true) } }
    }

    /** The first line of [text], cut at [MAX_LABEL] characters; "…" when it was cut or more lines follow. */
    fun label(text: String): String {
        val lines = text.trim().lines()
        val first = lines.first().trimEnd()
        val cut = first.length > MAX_LABEL
        return (if (cut) first.take(MAX_LABEL) else first) + if (cut || lines.size > 1) "…" else ""
    }

    /**
     * Where [statement] goes in [content]: over the selection [selStart, selEnd) when there is one, else at the caret
     * on an empty line, else on a new line after the caret's line.
     */
    fun insertion(
        content: String,
        selStart: Int,
        selEnd: Int,
        caret: Int,
        statement: String,
    ): Edit {
        if (selEnd > selStart) return Edit(selStart, selEnd, statement)
        val at = caret.coerceIn(0, content.length)
        val lineStart = content.lastIndexOf('\n', at - 1) + 1
        val lineEnd = content.indexOf('\n', at).let { if (it < 0) content.length else it }
        return if (content.substring(lineStart, lineEnd).isBlank()) {
            Edit(at, at, statement)
        } else {
            Edit(lineEnd, lineEnd, "\n$statement")
        }
    }
}
