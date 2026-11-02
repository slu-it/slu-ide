package dev.sluide.databases.result

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class PageStateTest {
    @ParameterizedTest
    @CsvSource(
        "500,0,500,false,,false,1–500 of 500+",
        "500,1,231,true,731,false,501–731 of 731",
        "500,1,231,true,,false,501–731 of 731",
        "100000,0,100000,false,100000,true,1–100000 of 100000+ (limit)",
        "500,0,0,true,0,false,0 rows",
        "500,0,12,true,12,false,1–12 of 12",
        "500,2,500,false,1500,false,1001–1500 of 1500+",
    )
    fun `label`(
        size: Int,
        index: Int,
        rows: Int,
        last: Boolean,
        known: Int?,
        capped: Boolean,
        expected: String,
    ) {
        assertEquals(expected, PageState(size, index, rows, last, known, capped).label)
    }

    @ParameterizedTest
    @CsvSource(
        "0,false,false,false,true,true",
        "0,true,false,false,false,false",
        "3,false,true,true,true,true",
        "3,true,true,true,false,false",
    )
    fun `enablement`(
        index: Int,
        last: Boolean,
        first: Boolean,
        previous: Boolean,
        next: Boolean,
        lastEnabled: Boolean,
    ) {
        val s = PageState(10, index, 10, last, null, false)
        assertEquals(first, s.canFirst)
        assertEquals(previous, s.canPrevious)
        assertEquals(next, s.canNext)
        assertEquals(lastEnabled, s.canLast)
    }
}
