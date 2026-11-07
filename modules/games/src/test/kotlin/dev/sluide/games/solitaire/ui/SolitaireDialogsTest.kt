package dev.sluide.games.solitaire.ui

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class SolitaireDialogsTest {
    @ParameterizedTest
    @CsvSource("1,1", "' 42 ',42", "1000000,1000000", "0,", "1000001,", "-5,", "abc,", "1.5,", "' ',")
    fun `a deal number is an integer from 1 to 1,000,000`(
        text: String,
        expected: Int?,
    ) {
        assertEquals(expected, parseDealNumber(text))
    }
}
