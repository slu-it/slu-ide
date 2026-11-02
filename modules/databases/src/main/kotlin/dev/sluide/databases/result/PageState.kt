package dev.sluide.databases.result

/** Labels and enablement of the paging toolbar. [pageIndex] counts from 0; [knownRowCount] is the rows fetched so far, if known. */
data class PageState(
    val pageSize: Int,
    val pageIndex: Int,
    val rowsOnPage: Int,
    val lastPage: Boolean,
    val knownRowCount: Int?,
    val capped: Boolean,
) {
    /** "1–500 of 500+", "501–731 of 731", "1–100000 of 100000+ (limit)", "0 rows". */
    val label: String
        get() {
            if (rowsOnPage == 0) return "0 rows"
            val first = pageIndex * pageSize + 1
            val end = first + rowsOnPage - 1
            val total = maxOf(knownRowCount ?: end, end)
            val suffix =
                when {
                    capped -> "+ (limit)"
                    lastPage -> ""
                    else -> "+"
                }
            return "$first–$end of $total$suffix"
        }

    val canFirst: Boolean get() = pageIndex > 0
    val canPrevious: Boolean get() = pageIndex > 0
    val canNext: Boolean get() = !lastPage
    val canLast: Boolean get() = !lastPage
}
