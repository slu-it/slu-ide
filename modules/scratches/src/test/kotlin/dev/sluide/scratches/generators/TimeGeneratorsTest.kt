package dev.sluide.scratches.generators

import dev.sluide.scratches.GeneratorContext
import dev.sluide.scratches.GeneratorGroup
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeGeneratorsTest {
    private fun text(instant: String): String {
        val clock = Clock.fixed(Instant.parse(instant), ZoneId.of("UTC"))
        return TIME_GENERATORS.single().generate(GeneratorContext(clock = clock, zone = ZoneId.of("Europe/Berlin")))
    }

    @Test
    fun `describes the entry`() {
        val generator = TIME_GENERATORS.single()
        assertEquals(GeneratorGroup.TIME, generator.group)
        assertEquals("Current Time (all formats)", generator.name)
        assertEquals("time.txt", generator.fileName)
    }

    @Test
    fun `writes all formats with aligned values`() {
        assertEquals(
            """
            Epoch seconds       1793361600
            Epoch milliseconds  1793361600123
            ISO-8601 UTC        2026-10-30T12:00:00.123Z
            ISO-8601 local      2026-10-30T13:00:00.123+01:00
            ISO-8601 with zone  2026-10-30T13:00:00.123+01:00[Europe/Berlin]
            RFC 1123 (HTTP)     Fri, 30 Oct 2026 12:00:00 GMT
            Local date-time     2026-10-30 13:00:00
            SQL timestamp UTC   2026-10-30 12:00:00.123
            Date                2026-10-30
            ISO week date       2026-W44-5
            Ordinal date        2026-303

            """.trimIndent(),
            text("2026-10-30T12:00:00.123Z"),
        )
    }

    @Test
    fun `keeps zero milliseconds and uses the local date across midnight`() {
        val lines = text("2026-12-31T23:30:00Z").lines()
        assertEquals("ISO-8601 UTC        2026-12-31T23:30:00.000Z", lines[2])
        assertEquals("ISO-8601 local      2027-01-01T00:30:00.000+01:00", lines[3])
        assertEquals("Date                2027-01-01", lines[8])
        assertEquals("ISO week date       2026-W53-5", lines[9])
        assertEquals("Ordinal date        2027-001", lines[10])
    }

    @Test
    fun `pads a single-digit day in the HTTP date`() {
        assertEquals("RFC 1123 (HTTP)     Fri, 06 Nov 2026 12:00:00 GMT", text("2026-11-06T12:00:00Z").lines()[5])
    }
}
