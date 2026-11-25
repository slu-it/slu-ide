package dev.sluide.pdf.view

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageFieldTest {
    @ParameterizedTest
    @CsvSource("1, 0", "12, 11", "' 7 ', 6", "40, 39", "41, 39", "0, 0", "-5, 0", "99999999999999999999, 39")
    fun `a number jumps to that page, kept within the document`(
        text: String,
        page: Int,
    ) {
        assertEquals(page, pageFieldTarget(text, 40))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "abc", "1.5", "3a", "1 2"])
    fun `anything but a whole number jumps nowhere`(text: String) {
        assertNull(pageFieldTarget(text, 40))
    }
}
