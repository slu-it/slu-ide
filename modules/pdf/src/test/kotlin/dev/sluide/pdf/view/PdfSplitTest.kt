package dev.sluide.pdf.view

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class PdfSplitTest {
    @ParameterizedTest
    @CsvSource(
        ", 220, 1000, 0.22",
        ", 220, 400, 0.35",
        "300, 220, 1000, 0.3",
        "800, 220, 1000, 0.45",
    )
    fun `a panel takes the width it was left at, or its first width, within a share of the split`(
        kept: Double?,
        first: Double,
        splitWidth: Double,
        share: Double,
    ) {
        assertEquals(share, panelShare(kept, first, splitWidth), 1e-9)
    }
}
