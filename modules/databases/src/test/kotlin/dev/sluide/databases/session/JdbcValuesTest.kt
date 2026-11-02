package dev.sluide.databases.session

import org.postgresql.util.PGobject
import java.math.BigDecimal
import java.sql.Timestamp
import java.sql.Types
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

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
}
