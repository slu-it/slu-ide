package dev.sluide.databases.ui.result

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.result.TableDataQuery
import dev.sluide.databases.session.ColumnInfo

/** What the replace decision needs to know of a result tab. */
internal data class TabInfo(
    val pinned: Boolean,
    /** A data tab (opened from the tree); never replaced by a console result. */
    val data: Boolean,
)

/** The decisions of the result area that need no controls: which tab a result replaces, titles, the status text. */
internal object ResultTabsLogic {
    const val DEFAULT_TITLE = "Result"
    const val OUTPUT_TITLE = "Output"
    private const val MIN_PAGE_SIZE = 10
    private const val MAX_PAGE_SIZE = 10_000

    /** The index (among the result tabs, Output not counted) of the first unpinned console tab, else null: add one. */
    fun replaceIndex(tabs: List<TabInfo>): Int? = tabs.indexOfFirst { !it.pinned && !it.data }.takeIf { it >= 0 }

    /** The one table all columns come from, else "Result". */
    fun title(columns: List<ColumnInfo>): String {
        val tables = columns.map { it.table }.toSet()
        return tables.singleOrNull() ?: DEFAULT_TITLE
    }

    fun pinLabel(pinned: Boolean): String = if (pinned) "Unpin Tab" else "Pin Tab"

    /** "23 ms", plus ", re-executed" and ", limit reached" where they apply. */
    fun status(
        millis: Long,
        reExecuted: Boolean,
        capped: Boolean,
    ): String =
        buildList {
            add(OutputText.duration(millis))
            if (reExecuted) add("re-executed")
            if (capped) add("limit reached")
        }.joinToString(", ")

    /**
     * The ORDER BY text after a click on [column]'s header in a data tab: ascending, or descending when [current] is
     * already that column ascending.
     */
    fun nextOrderBy(
        kind: DatabaseKind,
        current: String,
        column: String,
    ): String {
        val ascending = TableDataQuery.orderBy(kind, column, true)
        return if (current.trim() == ascending) TableDataQuery.orderBy(kind, column, false) else ascending
    }

    /** The page size typed in the field: blank = [default]; a number is limited to 10..10000; else null (invalid). */
    fun parsePageSize(
        text: String,
        default: Int,
    ): Int? {
        val t = text.trim()
        if (t.isEmpty()) return default
        return t.toIntOrNull()?.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
    }
}
