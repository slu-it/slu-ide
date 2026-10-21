package dev.sluide.previews.table

import dev.sluide.previews.preview.HtmlPage
import org.jsoup.nodes.Entities
import java.util.Locale

/**
 * Renders CSV and TSV as a table page for the preview (ADR 0017, ADR 0072): the first row as header if it looks like
 * one, a sort on a label click and a search field per column (`csv-table.js`), every row with the source line it
 * starts on (`data-line`, for scroll sync), at most [MAX_ROWS] rows below the first.
 */
internal object CsvRenderer {
    const val MAX_ROWS = 10_000

    /** A record of the file, starting on source [line] (0-based). */
    data class Row(
        val line: Int,
        val cells: List<String>,
    )

    /**
     * The records of [text] as RFC 4180 reads them: fields separated by [delimiter], quoted fields may contain the
     * delimiter, line breaks and `""` for a quote. Blank lines are skipped; `\r\n` line ends are fine. Without
     * [quotes], quotes are part of the fields (cookie values may be quoted).
     */
    fun parse(
        text: String,
        delimiter: Char,
        quotes: Boolean = true,
    ): List<Row> {
        val rows = mutableListOf<Row>()
        // Excel's "CSV UTF-8" starts with a byte order mark.
        val text = text.removePrefix("\uFEFF")
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var line = 0
        var rowLine = 0
        var quoted = false
        var fieldStart = true
        var i = 0

        fun endRow() {
            cells += cell.toString()
            cell.clear()
            if (cells.size > 1 || cells[0].isNotEmpty()) rows += Row(rowLine, cells.toList())
            cells.clear()
            fieldStart = true
        }

        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    cell.append('"')
                    i++
                }

                quoted && c == '"' -> {
                    quoted = false
                }

                quoted -> {
                    if (c == '\n') line++
                    cell.append(c)
                }

                c == '"' && fieldStart && quotes -> {
                    quoted = true
                    fieldStart = false
                }

                c == delimiter -> {
                    cells += cell.toString()
                    cell.clear()
                    fieldStart = true
                }

                c == '\r' && text.getOrNull(i + 1) == '\n' -> {}

                c == '\n' -> {
                    endRow()
                    line++
                    rowLine = line
                }

                else -> {
                    cell.append(c)
                    fieldStart = false
                }
            }
            i++
        }
        if (cell.isNotEmpty() || cells.isNotEmpty()) endRow()
        return rows
    }

    /** Tab for `.tsv`; otherwise `,`, or `;` if the first line has more of those (common in German Excel exports). */
    fun delimiter(
        extension: String,
        text: String,
    ): Char {
        if (extension.lowercase() == "tsv") return '\t'
        val first = text.lineSequence().firstOrNull().orEmpty()
        return if (first.count { it == ';' } > first.count { it == ',' }) ';' else ','
    }

    private val stylesheet = HtmlPage.resource("preview.css")
    private val script = HtmlPage.resource("preview.js")
    private val tableScript = HtmlPage.resource("csv-table.js")

    /**
     * The table page of [text], parsed with [delimiter] and [quotes] as [parse] does. The first record is the header
     * if [state] says so, else if [hasHeader] guesses so; with [fixedHeader] it always is and there is no checkbox.
     * The table carries [state] for `csv-table.js`, which restores its sort and filters.
     */
    fun render(
        text: String,
        delimiter: Char,
        dark: Boolean,
        quotes: Boolean = true,
        fixedHeader: Boolean = false,
        state: TableState = TableState(),
    ): String {
        val rows = parse(text, delimiter, quotes)
        val first = rows.firstOrNull()
        val columns = rows.maxOfOrNull { it.cells.size } ?: 0
        // With `;` between fields, numbers have decimal commas (`1,5`).
        val decimalComma = delimiter == ';'
        return HtmlPage.render(dark, listOf(stylesheet), listOf(script, tableScript), listOf("csv")) {
            if (first == null) {
                append("<p class=\"csv-note\">Empty file</p>\n")
            } else {
                appendTable(rows, columns, first, decimalComma, fixedHeader, state)
            }
        }
    }

    private fun StringBuilder.appendTable(
        rows: List<Row>,
        columns: Int,
        first: Row,
        decimalComma: Boolean,
        fixedHeader: Boolean,
        state: TableState,
    ) {
        val header = fixedHeader || (state.header ?: hasHeader(rows, decimalComma))
        val restored =
            TableState(
                header = if (fixedHeader) null else state.header,
                sortColumn = state.sortColumn?.takeIf { it < columns },
                descending = state.descending,
                filters = state.filters.take(columns),
            )
        append("<div class=\"csv-bar\">")
        if (!fixedHeader) {
            append("<label><input type=\"checkbox\" class=\"csv-header-toggle\"")
            if (header) append(" checked")
            append("> First row is header</label>")
        }
        append("<span class=\"csv-count\"></span></div>\n")
        append("<table class=\"csv-table").append(if (header) " has-header" else "").append("\"")
        append(" data-decimal=\"").append(if (decimalComma) "," else ".").append("\"")
        append(" data-header=\"").append(header).append("\"")
        append(" data-state=\"").append(HtmlPage.escape(restored.json())).append("\">\n")
        append("<thead><tr class=\"csv-labels\"")
        if (header) append(" data-line=\"").append(first.line).append("\"")
        append(">")
        for (column in 0 until columns) {
            val label = if (header) first.cells.getOrElse(column) { "" } else letters(column)
            append("<th>").append(escape(label)).append("</th>")
        }
        append("</tr>\n<tr class=\"csv-filters\">")
        for (column in 0 until columns) {
            append("<td><input type=\"text\" class=\"csv-filter\" spellcheck=\"false\"")
            val filter = restored.filters.getOrElse(column) { "" }
            if (filter.isNotEmpty()) append(" value=\"").append(HtmlPage.escape(filter)).append("\"")
            append("></td>")
        }
        append("</tr></thead>\n<tbody>\n")
        for ((index, row) in rows.take(MAX_ROWS + 1).withIndex()) {
            append("<tr").append(if (index == 0) " class=\"csv-first\"" else "")
            append(" data-line=\"").append(row.line).append("\">")
            for (column in 0 until columns) {
                append("<td>").append(escape(row.cells.getOrElse(column) { "" })).append("</td>")
            }
            append("</tr>\n")
        }
        append("</tbody>\n</table>\n")
        val shown = minOf(rows.size, MAX_ROWS + 1)
        if (rows.size > shown) {
            // In data rows: without a header the first record counts, too; csv-table.js rewrites it on a toggle.
            val skip = if (header) 1 else 0
            append("<p class=\"csv-note csv-limit\" data-shown=\"").append(shown)
            append("\" data-records=\"").append(rows.size).append("\">Showing the first ")
            append("%,d".format(Locale.ROOT, shown - skip)).append(" of ")
            append("%,d".format(Locale.ROOT, rows.size - skip))
            append(" rows. Search covers only the rows shown.</p>\n")
        }
    }

    /** The spreadsheet name of [column] (0-based): `A` to `Z`, then `AA`, `AB` and so on. */
    fun letters(column: Int): String {
        var rest = column + 1
        return buildString {
            while (rest > 0) {
                insert(0, 'A' + (rest - 1) % 26)
                rest = (rest - 1) / 26
            }
        }
    }

    private fun escape(text: String) = Entities.escape(text)
}
