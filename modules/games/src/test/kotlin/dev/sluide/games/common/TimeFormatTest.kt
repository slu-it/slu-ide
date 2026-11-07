package dev.sluide.games.common

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class TimeFormatTest {
    @ParameterizedTest
    @CsvSource("0,0:00", "5000,0:05", "5999,0:05", "252000,4:12", "3599999,59:59", "3723000,1:02:03", "-5,0:00")
    fun `formats whole seconds floored`(
        millis: Long,
        expected: String,
    ) {
        assertEquals(expected, formatTime(millis))
    }
}
