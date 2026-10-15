package dev.sluide.fileviewers

import org.jsoup.nodes.Entities
import java.util.Locale

/**
 * Renders CSV and TSV as a table page for the preview (ADR 0017): the first row as header (click to sort), every row
 * with the source line it starts on (`data-line`, for scroll sync), at most [MAX_ROWS] rows.
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

    /** The table page of [text], parsed with [delimiter] and [quotes] as [parse] does. */
    fun render(
        text: String,
        delimiter: Char,
        dark: Boolean,
        quotes: Boolean = true,
    ): String {
        val rows = parse(text, delimiter, quotes)
        val header = rows.firstOrNull()
        val body = rows.drop(1)
        val columns = rows.maxOfOrNull { it.cells.size } ?: 0
        return HtmlPage.render(dark, listOf(stylesheet), listOf(script, tableScript), listOf("csv")) {
            if (header == null) {
                append("<p class=\"csv-note\">Empty file</p>\n")
            } else {
                // With `;` between fields, numbers have decimal commas (`1,5`).
                val decimal = if (delimiter == ';') "," else "."
                append("<table class=\"csv-table\" data-decimal=\"").append(decimal).append("\">\n")
                append("<thead><tr data-line=\"").append(header.line).append("\">")
                for (column in 0 until columns) {
                    append("<th>").append(escape(header.cells.getOrElse(column) { "" })).append("</th>")
                }
                append("</tr></thead>\n<tbody>\n")
                for (row in body.take(MAX_ROWS)) {
                    append("<tr data-line=\"").append(row.line).append("\">")
                    for (column in 0 until columns) {
                        append("<td>").append(escape(row.cells.getOrElse(column) { "" })).append("</td>")
                    }
                    append("</tr>\n")
                }
                append("</tbody>\n</table>\n")
                if (body.size > MAX_ROWS) {
                    append("<p class=\"csv-note\">Showing the first ").append("%,d".format(Locale.ROOT, MAX_ROWS))
                    append(" of ").append("%,d".format(Locale.ROOT, body.size)).append(" rows.</p>\n")
                }
            }
        }
    }

    private fun escape(text: String) = Entities.escape(text)
}
