package dev.sluide.converting.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ConvertingTypesTest {
    @ParameterizedTest
    @CsvSource(
        "0, 1, 1",
        "2, 1, 3",
        "3, 2, 1",
        "5, 2, 3",
        "6, 3, 1",
        "7, 3, 1",
        "-1, 1, 1",
    )
    fun `lineColumn counts lines and columns from 1`(
        offset: Int,
        line: Int,
        column: Int,
    ) {
        assertEquals(line to column, lineColumn("ab\ncd\n", offset))
    }

    @Test
    fun `an info status shows the format, the characters and the notes`() {
        assertEquals(
            "YAML · detected · 1 character · 2 nulls dropped, 1 comment dropped",
            Status.Info("YAML · detected", 1, listOf("2 nulls dropped", "1 comment dropped")).text,
        )
        assertEquals("0 characters", Status.Info(null, 0, emptyList()).text)
    }

    @Test
    fun `a failure status shows its line and column if known`() {
        assertEquals("Line 3, column 5: bad", Status.Failure("bad", TextPosition(20, 3, 5)).text)
        assertEquals("bad", Status.Failure("bad", null).text)
    }
}
