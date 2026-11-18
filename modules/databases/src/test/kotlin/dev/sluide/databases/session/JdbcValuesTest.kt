package dev.sluide.databases.session

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.postgresql.util.PGobject
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Blob
import java.sql.Clob
import java.sql.SQLException
import java.sql.SQLXML
import java.sql.Timestamp
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JdbcValuesTest {
    private fun column(
        typeName: String,
        jdbcType: Int,
    ) = ColumnInfo("c", "c", typeName, jdbcType, null, null, nullable = true, autoIncrement = false)

    @Test
    fun `plain values stay as they are, JDBC and driver objects become plain ones`() {
        val uuid = UUID.randomUUID()
        val decimal = BigDecimal("1.50")
        assertSame(uuid, JdbcValues.plain(uuid))
        assertSame(decimal, JdbcValues.plain(decimal))
        assertEquals(
            "{\"a\": 1}",
            JdbcValues.plain(
                PGobject().apply {
                    type = "jsonb"
                    value = "{\"a\": 1}"
                },
            ),
        )
        assertEquals(LocalDateTime.of(2026, 11, 2, 10, 0), JdbcValues.plain(Timestamp.valueOf("2026-11-02 10:00:00")))
        assertEquals(listOf(1, 2), JdbcValues.plain(intArrayOf(1, 2)))
        assertEquals(listOf("a", null), JdbcValues.plain(arrayOf("a", null)))
        assertContentEquals(byteArrayOf(1, 2), JdbcValues.plain(byteArrayOf(1, 2)) as ByteArray)
        assertEquals("other", JdbcValues.plain(StringBuilder("other")))
        assertEquals(LocalDate.of(2026, 3, 4), JdbcValues.plain(java.sql.Date.valueOf("2026-03-04")))
        assertEquals(LocalTime.of(5, 6, 7), JdbcValues.plain(java.sql.Time.valueOf("05:06:07")))
        assertEquals(OffsetTime.MAX, JdbcValues.plain(OffsetTime.MAX))
        assertEquals(listOf(1L, 2L), JdbcValues.plain(longArrayOf(1, 2)))
        assertEquals(listOf<Short>(3), JdbcValues.plain(shortArrayOf(3)))
        assertEquals(listOf(1.5), JdbcValues.plain(doubleArrayOf(1.5)))
        assertEquals(listOf(2.5f), JdbcValues.plain(floatArrayOf(2.5f)))
        assertEquals(listOf(true, false), JdbcValues.plain(booleanArrayOf(true, false)))
        assertEquals(listOf(listOf(1, 2), null), JdbcValues.plain(arrayOf(arrayOf(1, 2), null)))
        assertEquals("abc", JdbcValues.plain(charArrayOf('a', 'b', 'c')))
    }

    @Test
    fun `a PostgreSQL bit column is read as text, MariaDB's BIT as the driver's boolean`() {
        val resultSet =
            java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(java.sql.ResultSet::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getString" -> "1"
                    "getObject" -> true
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as java.sql.ResultSet

        assertEquals(listOf<Any?>("1"), JdbcValues.row(resultSet, listOf(column("bit", Types.BIT))))
        assertEquals(listOf<Any?>(true), JdbcValues.row(resultSet, listOf(column("BIT", Types.BIT))))
    }

    @Test
    fun `temporal columns are read as java time, PostgreSQL's timestamptz with its offset`() {
        assertEquals(OffsetDateTime::class.java, JdbcValues.temporalClass(column("timestamptz", Types.TIMESTAMP)))
        assertEquals(LocalDateTime::class.java, JdbcValues.temporalClass(column("timestamp", Types.TIMESTAMP)))
        assertEquals(null, JdbcValues.temporalClass(column("YEAR", Types.DATE)))
        assertEquals(null, JdbcValues.temporalClass(column("int4", Types.INTEGER)))
    }

    private inline fun <reified T> fake(crossinline handler: (method: String, args: Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(T::class.java)) { _, method, args ->
            handler(method.name, args ?: emptyArray())
        } as T

    @Test
    fun `a JDBC array is read out and freed`() {
        val calls = mutableListOf<String>()
        val array =
            fake<java.sql.Array> { m, _ ->
                calls += m
                if (m == "getArray") arrayOf<Any?>(1, "x", null) else null
            }

        assertEquals(listOf<Any?>(1, "x", null), JdbcValues.plain(array))
        assertEquals(listOf("getArray", "free"), calls)
    }

    @Test
    fun `a JDBC array without content is null, one that is not an array is a list of its value`() {
        assertNull(JdbcValues.plain(fake<java.sql.Array> { _, _ -> null }))
        val single = fake<java.sql.Array> { m, _ -> if (m == "getArray") "one" else null }
        assertEquals(listOf<Any?>("one"), JdbcValues.plain(single))
    }

    @ParameterizedTest
    @CsvSource("true", "false")
    fun `a blob, a clob and an xml value are read whole and freed, a failing free changes nothing`(freeFails: Boolean) {
        fun freed(): Any? = if (freeFails) throw SQLException("free failed") else null
        val calls = mutableListOf<String>()
        val blob =
            fake<Blob> { m, args ->
                calls += "blob $m ${args.toList()}"
                when (m) {
                    "length" -> 3L
                    "getBytes" -> byteArrayOf(7, 8, 9)
                    else -> freed()
                }
            }
        val clob =
            fake<Clob> { m, args ->
                calls += "clob $m ${args.toList()}"
                when (m) {
                    "length" -> 4L
                    "getSubString" -> "text"
                    else -> freed()
                }
            }
        val xml =
            fake<SQLXML> { m, _ ->
                calls += "xml $m"
                if (m == "getString") "<a/>" else freed()
            }

        assertContentEquals(byteArrayOf(7, 8, 9), JdbcValues.plain(blob) as ByteArray)
        assertEquals("text", JdbcValues.plain(clob))
        assertEquals("<a/>", JdbcValues.plain(xml))

        assertTrue("blob getBytes [1, 3]" in calls && "blob free []" in calls, calls.toString())
        assertTrue("clob getSubString [1, 4]" in calls && "clob free []" in calls, calls.toString())
        assertTrue("xml getString" in calls && "xml free" in calls, calls.toString())
    }
}
