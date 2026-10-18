package dev.sluide.gitlog.table

import kotlin.test.Test
import kotlin.test.assertEquals

class FitColumnWidthsTest {
    private fun fit(contentWidth: Double) =
        fitColumnWidths(contentWidth, 80.0, listOf(160.0, 140.0), listOf(50.0, 50.0))

    @Test
    fun `the first column takes the rest while it has its minimum width`() {
        assertEquals(listOf(700.0, 160.0, 140.0), fit(1000.0))
        assertEquals(listOf(80.0, 160.0, 140.0), fit(380.0))
    }

    @Test
    fun `below that the others shrink proportionally towards their minimum widths`() {
        // 100 too little: 110 and 90 above their minimums give 55 and 45.
        assertEquals(listOf(80.0, 105.0, 95.0), fit(280.0))
    }

    @Test
    fun `below all minimum widths every column is at its minimum`() {
        assertEquals(listOf(80.0, 50.0, 50.0), fit(180.0))
        assertEquals(listOf(80.0, 50.0, 50.0), fit(100.0))
    }

    @Test
    fun `others already at their minimum stay there`() {
        assertEquals(listOf(80.0, 50.0), fitColumnWidths(100.0, 80.0, listOf(50.0), listOf(50.0)))
    }
}
