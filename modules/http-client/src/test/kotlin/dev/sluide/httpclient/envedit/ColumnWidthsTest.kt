package dev.sluide.httpclient.envedit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ColumnWidthsTest {
    private fun widths(
        width: Double,
        envCount: Int,
    ) = EnvironmentTable.columnWidths(width, typeWidth = 110.0, envCount = envCount)

    @Test
    fun `a wide table gives Name its full width and shares the rest`() {
        assertEquals(250.0 to 310.0, widths(1000.0, 2))
    }

    @Test
    fun `Name takes a share of a medium table`() {
        assertEquals(210.0 to 180.0, widths(700.0, 2))
    }

    @Test
    fun `a narrow table keeps the minimum widths and scrolls sideways`() {
        assertEquals(160.0 to 140.0, widths(400.0, 2))
    }

    @Test
    fun `without environments only Name is sized`() {
        val (name, each) = widths(1000.0, 0)

        assertEquals(250.0, name)
        assertNull(each)
    }
}
