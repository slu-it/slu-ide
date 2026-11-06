package dev.sluide.fileviewers

/**
 * What the row layout of a [WrappingToolbar] knows of one of its nodes: its preferred and minimum width, its left and
 * right margin and whether it is a [separator], which is not shown at a row's start or end.
 */
internal data class ToolbarUnit(
    val prefWidth: Double,
    val minWidth: Double = prefWidth,
    val leftMargin: Double = 0.0,
    val rightMargin: Double = 0.0,
    val separator: Boolean = false,
)

/**
 * Where a unit goes: its [row], the [x] of the node itself (after its left margin) from the left edge of the content
 * and its [width]. A separator at a row's start or end is not [shown] and has no width.
 */
internal data class UnitPlace(
    val row: Int,
    val x: Double,
    val width: Double,
    val shown: Boolean = true,
)

/** The places of the [leading] and [trailing] units, in their order, and the number of rows with a shown unit. */
internal data class ToolbarRows(
    val leading: List<UnitPlace>,
    val trailing: List<UnitPlace>,
    val count: Int,
)

/**
 * Lays out a toolbar's units in rows [available] wide (ADR 0135). The [trailing] units stay at the right end of the
 * first row; the [leading] units fill the first row from the left up to [groupGap] before them and continue on further
 * rows at full width. Only when the trailing units alone are wider than [available] do they wrap themselves, each of
 * their rows right-aligned, and the leading units start on the row after them. Units on a row are [hgap] apart (plus
 * their margins, as in an `HBox`); a unit starting a row drops its left margin. A unit alone on a row and wider than it
 * gets the row's width, but not less than its minimum width.
 */
internal fun toolbarRows(
    available: Double,
    leading: List<ToolbarUnit>,
    trailing: List<ToolbarUnit>,
    hgap: Double,
    groupGap: Double,
): ToolbarRows {
    val right = flow(trailing, available, available, firstMayStayEmpty = false, hgap)
    val trailingPlaces =
        right.places.map { place ->
            // Right-aligned; a unit wider than the row overflows on the right, where the toolbar clips it.
            place.copy(x = place.x + maxOf(0.0, available - right.rowEnds[place.row]))
        }
    val left =
        when {
            right.count == 0 -> flow(leading, available, available, firstMayStayEmpty = false, hgap)
            right.count == 1 -> flow(leading, available - right.rowEnds[0] - groupGap, available, true, hgap)
            else -> flow(leading, available, available, firstMayStayEmpty = false, hgap).shifted(right.count)
        }
    return ToolbarRows(left.places, trailingPlaces, maxOf(left.count, right.count))
}

/** The width that shows [leading], [groupGap] and [trailing] on one row. */
internal fun toolbarPrefWidth(
    leading: List<ToolbarUnit>,
    trailing: List<ToolbarUnit>,
    hgap: Double,
    groupGap: Double,
): Double {
    val unbounded = Double.POSITIVE_INFINITY
    val left = flow(leading, unbounded, unbounded, firstMayStayEmpty = false, hgap)
    val right = flow(trailing, unbounded, unbounded, firstMayStayEmpty = false, hgap)
    val gap = if (left.count > 0 && right.count > 0) groupGap else 0.0
    return (left.rowEnds.firstOrNull() ?: 0.0) + gap + (right.rowEnds.firstOrNull() ?: 0.0)
}

/** Places per unit (rows counted from this group's first row), the used width per row and the rows with a shown unit. */
private class Flow(
    val places: List<UnitPlace>,
    val rowEnds: List<Double>,
    val count: Int,
) {
    fun shifted(rows: Int) =
        Flow(
            places.map { it.copy(row = it.row + rows) },
            List(rows) { 0.0 } + rowEnds,
            if (count == 0) 0 else count + rows,
        )
}

private const val EPSILON = 1e-6

/**
 * Packs [units] left to right into rows: the first [firstWidth] wide, the others [width]. With [firstMayStayEmpty], a
 * unit that does not fit the first row even alone goes to the second; otherwise a unit alone on a row is shrunk to it.
 */
private fun flow(
    units: List<ToolbarUnit>,
    firstWidth: Double,
    width: Double,
    firstMayStayEmpty: Boolean,
    hgap: Double,
): Flow {
    val places = mutableListOf<UnitPlace>()
    val rowEnds = mutableListOf<Double>()
    // The shown units of the current row, by index.
    val members = mutableListOf<Int>()
    var row = 0
    var end = 0.0

    fun rowWidth() = if (row == 0) firstWidth else width

    fun closeRow() {
        while (members.isNotEmpty() && units[members.last()].separator) {
            val last = members.removeAt(members.lastIndex)
            places[last] = places[last].copy(width = 0.0, shown = false)
        }
        rowEnds += members.lastOrNull()?.let { places[it].x + places[it].width + units[it].rightMargin } ?: 0.0
        members.clear()
        end = 0.0
        row++
    }

    units.forEachIndexed { index, unit ->
        if (members.isNotEmpty()) {
            val x = end + hgap + unit.leftMargin
            if (x + unit.prefWidth + unit.rightMargin <= rowWidth() + EPSILON) {
                places += UnitPlace(row, x, unit.prefWidth)
                members += index
                end = x + unit.prefWidth + unit.rightMargin
                return@forEachIndexed
            }
            closeRow()
        }
        if (unit.separator) {
            places += UnitPlace(row, 0.0, 0.0, shown = false)
            return@forEachIndexed
        }
        if (row == 0 && firstMayStayEmpty && unit.prefWidth + unit.rightMargin > firstWidth + EPSILON) closeRow()
        val fitted = maxOf(minOf(unit.prefWidth, rowWidth() - unit.rightMargin), unit.minWidth)
        places += UnitPlace(row, 0.0, fitted)
        members += index
        end = fitted + unit.rightMargin
    }
    // A separator hidden at the start of a last row still gets that row's end.
    if (members.isNotEmpty() || places.any { it.row == row }) closeRow()
    val count = places.filter { it.shown }.maxOfOrNull { it.row + 1 } ?: 0
    return Flow(places, rowEnds, count)
}
