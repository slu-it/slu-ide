package dev.sluide.previews.table

/** The rows below the first that [hasHeader] looks at. */
private const val SAMPLE_ROWS = 200

private enum class CellType { NUMBER, DATE, TEXT }

private val numberPattern = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")
private val datePatterns =
    listOf(
        Regex("""\d{4}-\d{2}-\d{2}([T ].*)?"""),
        Regex("""\d{1,2}\.\d{1,2}\.\d{4}"""),
        Regex("""\d{1,2}/\d{1,2}/\d{4}"""),
    )

/**
 * Whether the first of [rows] is a header: each column whose cells below it (empty ones ignored) are all numbers or
 * all dates votes for a header if its first cell is of another type, against one if it is of the same type. The side
 * with more votes wins; a tie, no votes or fewer than two rows mean a header. Numbers are read as `csv-table.js`
 * reads them: `1,234.5`, or `1.234,5` with [decimalComma].
 */
internal fun hasHeader(
    rows: List<CsvRenderer.Row>,
    decimalComma: Boolean,
): Boolean {
    if (rows.size < 2) return true
    val first = rows[0].cells
    val sample = rows.subList(1, minOf(rows.size, SAMPLE_ROWS + 1))
    val columns = sample.maxOf { it.cells.size }
    var votes = 0
    for (column in 0 until columns) {
        val types =
            sample
                .mapNotNull {
                    it.cells
                        .getOrNull(column)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                }.map { type(it, decimalComma) }
                .toSet()
        val type = types.singleOrNull()?.takeIf { it != CellType.TEXT } ?: continue
        val title = first.getOrNull(column)?.trim().orEmpty()
        if (title.isEmpty()) continue
        votes += if (type(title, decimalComma) == type) -1 else 1
    }
    return votes >= 0
}

private fun type(
    cell: String,
    decimalComma: Boolean,
): CellType {
    if (datePatterns.any { it.matches(cell) }) return CellType.DATE
    val number = if (decimalComma) cell.replace(".", "").replaceFirst(',', '.') else cell.replace(",", "")
    return if (numberPattern.matches(number)) CellType.NUMBER else CellType.TEXT
}
