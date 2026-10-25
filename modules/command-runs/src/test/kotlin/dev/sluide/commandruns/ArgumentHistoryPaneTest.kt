package dev.sluide.commandruns

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class ArgumentHistoryPaneTest {
    @ParameterizedTest(name = "{0} rows: {1}")
    @CsvSource("0, 2", "1, 26", "5, 122", "6, 122", "20, 122")
    fun `the history list is as high as its rows, up to five`(
        rows: Int,
        height: Double,
    ) {
        assertEquals(height, listHeight(rows, cellSize = 24.0, insets = 2.0))
    }
}
