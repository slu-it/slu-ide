package dev.sluide.ide.explorer

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class WindowExplorerTest {
    @ParameterizedTest(name = "split {0}, width {1}, divider {2}: {3}")
    @CsvSource(
        // The width plus half a divider from the left edge.
        "1000, 240, 10, 0.245",
        "800, 195, 10, 0.25",
        // An unknown total uses a 1000 px wide split.
        "0, 240, 10, 0.245",
        "-1, 240, 10, 0.245",
        // A column wider than the split stays within 0 and 1.
        "200, 500, 8, 1.0",
    )
    fun `the divider is the column's width plus half a divider from the left edge`(
        total: Double,
        width: Double,
        dividerWidth: Double,
        expected: Double,
    ) {
        assertEquals(expected, explorerDividerPosition(total, width, dividerWidth), 1e-9)
    }
}
