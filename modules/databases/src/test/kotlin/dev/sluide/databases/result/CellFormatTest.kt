package dev.sluide.databases.result

import dev.sluide.databases.session.ColumnInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CellFormatTest {
    @Test
    fun `display of each value type`() {
        assertEquals("<null>", CellFormat.display(null))
        assertEquals("a⏎b⏎c⏎d", CellFormat.display("a\nb\r\nc\rd"))
        assertEquals("0x00ff10", CellFormat.display(byteArrayOf(0, -1, 16)))
        assertEquals("12345678901234567890.50", CellFormat.display(BigDecimal("12345678901234567890.50")))
        assertEquals("100000000", CellFormat.display(BigDecimal("1E+8")))
        assertEquals("2024-03-05", CellFormat.display(LocalDate.of(2024, 3, 5)))
        assertEquals("10:15:00", CellFormat.display(LocalTime.of(10, 15)))
        assertEquals("2024-03-05T10:15:30", CellFormat.display(LocalDateTime.of(2024, 3, 5, 10, 15, 30)))
        assertEquals(
            "2024-03-05T10:15:30+02:00",
            CellFormat.display(OffsetDateTime.parse("2024-03-05T10:15:30+02:00")),
        )
        assertEquals("{1,NULL,x}", CellFormat.display(listOf(1, null, "x")))
        assertEquals("true", CellFormat.display(true))
        assertEquals("", CellFormat.display(""))
    }

    @Test
    fun `display cuts text and bytes at maxChars`() {
        assertEquals("abc…", CellFormat.display("abcdef", 3))
        assertEquals("abc", CellFormat.display("abc", 3))
        assertEquals("0xabc…", CellFormat.display(byteArrayOf(-85, -51, -17), 5))
        assertEquals("0xabcdef", CellFormat.display(byteArrayOf(-85, -51, -17), 8))
    }

    @Test
    fun `full keeps line breaks and has no cut`() {
        assertEquals("", CellFormat.full(null))
        assertEquals("a\nb", CellFormat.full("a\nb"))
        assertEquals("0x0aff", CellFormat.full(byteArrayOf(10, -1)))
        assertEquals("x".repeat(5000), CellFormat.full("x".repeat(5000)))
        assertEquals("{1,2}", CellFormat.full(listOf(1, 2)))
        assertEquals("1.50", CellFormat.full(BigDecimal("1.50")))
    }

    @ParameterizedTest
    @CsvSource(
        "4,42,42",
        "-5,-9223372036854775808,-9223372036854775808",
        "4,+7,7",
        "-5,18446744073709551615,18446744073709551615",
        "3,12.50,12.50",
        "8,1e3,1E+3",
        "16,true,true",
        "16,T,true",
        "-7,0,false",
        "16,f,false",
    )
    fun `parses numbers and booleans`(
        type: Int,
        text: String,
        expected: String,
    ) {
        val value = CellFormat.parse(text, col("a", type))
        assertEquals(expected, value.toString())
        if (type == 4) assertTrue(value is Long)
        if (type == 16) assertTrue(value is Boolean)
    }

    @Test
    fun `parses dates times and timestamps`() {
        assertEquals(LocalDate.of(2024, 3, 5), CellFormat.parse("2024-03-05", col("a", Types.DATE)))
        assertEquals(LocalTime.of(10, 15, 30), CellFormat.parse("10:15:30", col("a", Types.TIME)))
        val ts = LocalDateTime.of(2024, 3, 5, 10, 15, 30)
        assertEquals(ts, CellFormat.parse("2024-03-05T10:15:30", col("a", Types.TIMESTAMP)))
        assertEquals(ts, CellFormat.parse("2024-03-05 10:15:30", col("a", Types.TIMESTAMP)))
        assertEquals(
            OffsetDateTime.parse("2024-03-05T10:15:30+02:00"),
            CellFormat.parse("2024-03-05 10:15:30+02:00", col("a", Types.TIMESTAMP, "timestamptz")),
        )
        assertEquals(
            OffsetDateTime.parse("2024-03-05T10:15:30Z"),
            CellFormat.parse("2024-03-05T10:15:30Z", col("a", Types.TIMESTAMP_WITH_TIMEZONE)),
        )
    }

    @Test
    fun `parses binary and uuid`() {
        assertContentEquals(
            byteArrayOf(0, -1, 16),
            CellFormat.parse("0x00FF10", col("a", Types.VARBINARY)) as ByteArray,
        )
        assertContentEquals(byteArrayOf(), CellFormat.parse("0x", col("a", Types.BLOB)) as ByteArray)
        val id = "123e4567-e89b-12d3-a456-426614174000"
        assertEquals(UUID.fromString(id), CellFormat.parse(id, col("a", Types.OTHER, "uuid")))
    }

    @Test
    fun `text is kept as typed and blank non-text is null`() {
        assertEquals("  a b ", CellFormat.parse("  a b ", col("a")))
        assertEquals("", CellFormat.parse("", col("a")))
        assertNull(CellFormat.parse("  ", col("a", Types.INTEGER)))
        assertNull(CellFormat.parse("", col("a", Types.DATE)))
    }

    @ParameterizedTest
    @CsvSource(
        "4,abc,Not a whole number",
        "4,1.5,Not a whole number",
        "3,x,Not a number",
        "16,maybe,Not a boolean (true or false)",
        "91,2024-13-01,Not a date (yyyy-mm-dd)",
        "92,25:00,Not a time (hh:mm:ss)",
        "93,yesterday,Not a timestamp (yyyy-mm-dd hh:mm:ss)",
        "-3,ff,Binary values are typed as 0x followed by hex digits",
        "-3,0xabc,Binary values are typed as 0x followed by hex digits",
        "-3,0xzz,Binary values are typed as 0x followed by hex digits",
    )
    fun `refuses text that does not parse`(
        type: Int,
        text: String,
        message: String,
    ) {
        val e = assertFailsWith<IllegalArgumentException> { CellFormat.parse(text, col("a", type)) }
        assertEquals(message, e.message)
    }

    @Test
    fun `refuses a malformed uuid`() {
        assertFailsWith<IllegalArgumentException> { CellFormat.parse("nope", col("a", Types.OTHER, "uuid")) }
    }

    @Test
    fun `a plain timestamp refuses an offset`() {
        assertFailsWith<IllegalArgumentException> {
            CellFormat.parse("2024-03-05 10:15:30+02:00", col("a", Types.TIMESTAMP, "timestamp"))
        }
        assertFailsWith<IllegalArgumentException> {
            CellFormat.parse("2024-03-05 10:15:30", col("a", Types.TIMESTAMP, "timestamptz"))
        }
    }

    @Test
    fun `arrays use postgresql array literal quoting`() {
        assertEquals("{\"a,b\",\"x\\\"y\",NULL}", CellFormat.full(listOf("a,b", "x\"y", null)))
        assertEquals(
            "{\"\",\"NULL\",\"a b\",\"{x}\",\"back\\\\slash\",plain}",
            CellFormat.display(listOf("", "NULL", "a b", "{x}", "back\\slash", "plain")),
        )
        assertEquals("{{1,2},{3,NULL}}", CellFormat.full(listOf(listOf(1, 2), listOf(3, null))))
    }

    @Test
    fun `year is a number, money and postgresql bit are text, mariadb bit a boolean`() {
        assertEquals(2024L, CellFormat.parse("2024", col("a", Types.SMALLINT, "YEAR")))
        assertEquals("\$1,234.50", CellFormat.parse("\$1,234.50", col("a", Types.DOUBLE, "money")))
        assertEquals("10110", CellFormat.parse("10110", col("a", Types.BIT, "bit")))
        assertEquals(true, CellFormat.parse("1", col("a", Types.BIT, "BIT")))
    }

    @ParameterizedTest
    @MethodSource("roundTrips")
    fun `parse undoes full`(
        value: Any,
        column: ColumnInfo,
    ) {
        assertEquals(value, CellFormat.parse(CellFormat.full(value), column))
    }

    companion object {
        @JvmStatic
        fun roundTrips(): List<Arguments> =
            listOf(
                Arguments.of(42L, col("a", Types.INTEGER)),
                Arguments.of(BigDecimal("12345678901234567890.50"), col("a", Types.DECIMAL)),
                Arguments.of(true, col("a", Types.BOOLEAN)),
                Arguments.of(LocalDate.of(2024, 3, 5), col("a", Types.DATE)),
                Arguments.of(LocalTime.of(10, 15, 30), col("a", Types.TIME)),
                Arguments.of(java.time.OffsetTime.parse("10:15:30+02:00"), col("a", Types.TIME, "timetz")),
                Arguments.of(LocalDateTime.of(2024, 3, 5, 10, 15, 30, 123_000_000), col("a", Types.TIMESTAMP)),
                Arguments.of(
                    OffsetDateTime.parse("2024-03-05T10:15:30.5+02:00"),
                    col("a", Types.TIMESTAMP, "timestamptz"),
                ),
                Arguments.of(UUID.randomUUID(), col("a", Types.OTHER, "uuid")),
                Arguments.of("text with spaces", col("a")),
                Arguments.of("{\"a\": [1, 2]}", col("a", Types.OTHER, "jsonb")),
                Arguments.of("{\"a,b\",NULL}", col("a", Types.ARRAY, "_text")),
                Arguments.of(2024L, col("a", Types.SMALLINT, "YEAR")),
                Arguments.of("10110", col("a", Types.BIT, "bit")),
            )
    }
}
