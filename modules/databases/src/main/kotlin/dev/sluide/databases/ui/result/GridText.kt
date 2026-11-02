package dev.sluide.databases.ui.result

import dev.sluide.databases.result.CellFormat
import dev.sluide.databases.session.ColumnInfo

/** Text and sizes of the result grid that need no controls. */
internal object GridText {
    private const val CHAR_WIDTH = 7.5
    private const val PADDING = 24.0
    private const val MIN_WIDTH = 60.0
    const val MAX_WIDTH = 400.0

    /** A column's width from its header label and the displayed texts of a sample of rows, 60..400 px. */
    fun columnWidth(
        label: String,
        sample: List<String>,
    ): Double {
        val chars = maxOf(label.length + 2, sample.maxOfOrNull { it.length } ?: 0)
        return (chars * CHAR_WIDTH + PADDING).coerceIn(MIN_WIDTH, MAX_WIDTH)
    }

    /** The width of the row-number column for rows up to [lastRow]. */
    fun rowNumberWidth(lastRow: Int): Double = lastRow.toString().length * 9.0 + 20.0

    /**
     * The rectangle over the rows and columns of [cells] (row, column) as tab-separated text without a final line
     * break: complete values ([CellFormat.full], null empty); a value with a tab, line break or quote is quoted with
     * doubled quotes. [withHeader] adds the column labels first.
     */
    fun copyText(
        columns: List<ColumnInfo>,
        rows: List<List<Any?>>,
        cells: List<Pair<Int, Int>>,
        withHeader: Boolean,
    ): String {
        if (cells.isEmpty()) return ""
        val rowIndexes = cells.map { it.first }.distinct().sorted()
        val columnIndexes = cells.map { it.second }.distinct().sorted()
        val lines = mutableListOf<String>()
        if (withHeader) lines += columnIndexes.joinToString("\t") { field(columns[it].label) }
        for (r in rowIndexes) lines += columnIndexes.joinToString("\t") { field(CellFormat.full(rows[r][it])) }
        return lines.joinToString("\n")
    }

    private fun field(text: String): String =
        if (text.any { it == '\t' || it == '\n' || it == '\r' || it == '"' }) {
            "\"" + text.replace("\"", "\"\"") + "\""
        } else {
            text
        }
}
