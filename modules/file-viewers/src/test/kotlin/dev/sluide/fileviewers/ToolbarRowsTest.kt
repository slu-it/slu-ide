package dev.sluide.fileviewers

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** The row layout of [WrappingToolbar] ([toolbarRows], [toolbarPrefWidth], ADR 0135); plain numbers, no display. */
class ToolbarRowsTest {
    private fun unit(width: Double) = ToolbarUnit(width)

    private val separator = ToolbarUnit(4.0, separator = true)

    private fun rows(
        available: Double,
        leading: List<ToolbarUnit>,
        trailing: List<ToolbarUnit> = emptyList(),
        hgap: Double = 2.0,
        groupGap: Double = 12.0,
    ) = toolbarRows(available, leading, trailing, hgap, groupGap)

    @Test
    fun `everything fits on one row, leading from the left and trailing at the right end`() {
        val rows = rows(300.0, listOf(unit(40.0), unit(30.0)), listOf(unit(20.0), unit(25.0)))

        assertEquals(listOf(UnitPlace(0, 0.0, 40.0), UnitPlace(0, 42.0, 30.0)), rows.leading)
        assertEquals(listOf(UnitPlace(0, 253.0, 20.0), UnitPlace(0, 275.0, 25.0)), rows.trailing)
        assertEquals(1, rows.count)
    }

    @Test
    fun `leading units wrap below at full width while the trailing ones stay at the end of the first row`() {
        val rows = rows(150.0, listOf(unit(60.0), unit(60.0), unit(60.0)), listOf(unit(20.0), unit(25.0)))

        assertEquals(
            listOf(UnitPlace(0, 0.0, 60.0), UnitPlace(1, 0.0, 60.0), UnitPlace(1, 62.0, 60.0)),
            rows.leading,
            "the first row ends 12 before the trailing units, later rows use the full width",
        )
        assertEquals(listOf(UnitPlace(0, 103.0, 20.0), UnitPlace(0, 125.0, 25.0)), rows.trailing)
        assertEquals(2, rows.count)
    }

    @Test
    fun `a leading unit that does not fit beside the trailing ones starts on the second row`() {
        val rows = rows(100.0, listOf(unit(60.0)), listOf(unit(50.0)))

        assertEquals(listOf(UnitPlace(1, 0.0, 60.0)), rows.leading)
        assertEquals(listOf(UnitPlace(0, 50.0, 50.0)), rows.trailing)
        assertEquals(2, rows.count)
    }

    @Test
    fun `trailing units wider than the toolbar wrap first, right-aligned, and the leading ones follow below`() {
        val rows = rows(100.0, listOf(unit(40.0)), listOf(unit(60.0), unit(60.0), unit(30.0)))

        assertEquals(
            listOf(UnitPlace(0, 40.0, 60.0), UnitPlace(1, 8.0, 60.0), UnitPlace(1, 70.0, 30.0)),
            rows.trailing,
        )
        assertEquals(listOf(UnitPlace(2, 0.0, 40.0)), rows.leading)
        assertEquals(3, rows.count)
    }

    @ParameterizedTest
    @CsvSource(
        "40, 100", // shrunk to the toolbar's width
        "120, 120", // not below its minimum width; the toolbar clips the rest
    )
    fun `a unit wider than the toolbar gets the toolbar's width, but not less than its minimum`(
        minWidth: Double,
        width: Double,
    ) {
        val wide = ToolbarUnit(150.0, minWidth)

        assertEquals(listOf(UnitPlace(0, 0.0, width)), rows(100.0, listOf(wide)).leading)
        val trailingOnly = rows(100.0, listOf(unit(30.0)), listOf(wide))
        assertEquals(listOf(UnitPlace(0, 0.0, width)), trailingOnly.trailing, "from the left edge, overflowing right")
        assertEquals(listOf(UnitPlace(1, 0.0, 30.0)), trailingOnly.leading)
    }

    @Test
    fun `a separator at a row's start or end is hidden without width, one between units stays`() {
        val units = listOf(separator, unit(40.0), separator, unit(40.0), separator, unit(40.0))

        val rows = rows(90.0, units)

        assertEquals(
            listOf(
                UnitPlace(0, 0.0, 0.0, shown = false),
                UnitPlace(0, 0.0, 40.0),
                UnitPlace(0, 42.0, 4.0),
                UnitPlace(0, 48.0, 40.0),
                UnitPlace(1, 0.0, 0.0, shown = false),
                UnitPlace(1, 0.0, 40.0),
            ),
            rows.leading,
        )
        val atEnd = rows(50.0, listOf(unit(40.0), separator, unit(40.0)))
        assertEquals(
            listOf(UnitPlace(0, 0.0, 40.0), UnitPlace(0, 42.0, 0.0, shown = false), UnitPlace(1, 0.0, 40.0)),
            atEnd.leading,
            "it fitted, but the unit after it did not",
        )
        val trailing = rows(100.0, emptyList(), listOf(unit(40.0), separator)).trailing
        assertEquals(UnitPlace(0, 60.0, 40.0), trailing.first(), "a hidden separator does not push the group left")
        assertEquals(1, rows(44.0, listOf(unit(40.0), separator)).count, "wrapped alone, no row of its own")
    }

    @Test
    fun `the left margin of a unit starting a row is dropped, the other margins apply`() {
        val units =
            listOf(ToolbarUnit(40.0, leftMargin = 10.0, rightMargin = 3.0), ToolbarUnit(40.0, leftMargin = 10.0))

        assertEquals(listOf(UnitPlace(0, 0.0, 40.0), UnitPlace(0, 55.0, 40.0)), rows(100.0, units).leading)
        assertEquals(listOf(UnitPlace(0, 0.0, 40.0), UnitPlace(1, 0.0, 40.0)), rows(80.0, units).leading)
    }

    @Test
    fun `with no leading or no trailing units the other group has the row to itself`() {
        assertEquals(listOf(UnitPlace(0, 80.0, 20.0)), rows(100.0, emptyList(), listOf(unit(20.0))).trailing)
        val leadingOnly = rows(100.0, listOf(unit(50.0), unit(48.0)))
        assertEquals(listOf(UnitPlace(0, 0.0, 50.0), UnitPlace(0, 52.0, 48.0)), leadingOnly.leading, "no group gap")
        assertEquals(0, rows(100.0, emptyList()).count)
    }

    @ParameterizedTest
    @CsvSource(
        "100, 0", // 35 + group gap 20 + 20 + hgap 5 + 20
        "99, 1",
    )
    fun `the leading units share the first row only with the group gap before the trailing ones`(
        available: Double,
        row: Int,
    ) {
        val rows = rows(available, listOf(unit(35.0)), listOf(unit(20.0), unit(20.0)), hgap = 5.0, groupGap = 20.0)

        assertEquals(row, rows.leading.single().row)
    }

    @Test
    fun `the preferred width puts all units on one row with the group gap between the groups`() {
        val leading = listOf(separator, unit(40.0), unit(30.0), separator)

        assertEquals(131.0, toolbarPrefWidth(leading, listOf(unit(20.0), unit(25.0)), 2.0, 12.0))
        assertEquals(72.0, toolbarPrefWidth(leading, emptyList(), 2.0, 12.0))
    }
}
