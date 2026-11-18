package dev.sluide.databases.session

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.postgresql.PGResultSetMetaData
import org.postgresql.util.PGobject
import java.lang.reflect.Proxy
import java.sql.Blob
import java.sql.Clob
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.SQLXML
import java.sql.Types
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The columns and values [JdbcValues] reads, from fake JDBC objects (the plain-object cases are in JdbcValuesTest). */
class JdbcValuesReadTest {
    private inline fun <reified T> fake(crossinline handler: (method: String, args: Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(T::class.java)) { _, method, args ->
            handler(method.name, args ?: emptyArray())
        } as T

    // Checked against the drivers' bytecode (javap, pgjdbc 42.7.13 and Connector/J 3.5.10): pgjdbc's
    // getBase{Schema,Table,Column}Name give "" when the server names none, and Connector/J's getSchemaName gives ""
    // (in its catalog mode, which the app uses) while getCatalogName gives the database.
    private class Meta(
        val names: List<String?>,
        val labels: List<String?> = names,
        val schemas: List<String?> = names.map { "" },
        val catalogs: List<String?> = names.map { "db" },
        val tables: List<String?> = names.map { "tbl" },
        val typeNames: List<String?> = names.map { "int4" },
        val nullable: List<Int> = names.map { ResultSetMetaData.columnNullable },
        val autoIncrement: List<Boolean> = names.map { false },
        val pg: Triple<List<String?>, List<String?>, List<String?>>? = null,
    )

    private fun metaData(meta: Meta): ResultSetMetaData {
        fun at(
            list: List<Any?>,
            args: Array<Any?>,
        ) = list[(args[0] as Int) - 1]
        lateinit var self: ResultSetMetaData
        self =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(ResultSetMetaData::class.java, PGResultSetMetaData::class.java),
            ) { _, method, given ->
                val args: Array<Any?> = given ?: emptyArray()
                when (method.name) {
                    "getColumnCount" -> meta.names.size
                    "getColumnName" -> at(meta.names, args)
                    "getColumnLabel" -> at(meta.labels, args)
                    "getSchemaName" -> at(meta.schemas, args)
                    "getCatalogName" -> at(meta.catalogs, args)
                    "getTableName" -> at(meta.tables, args)
                    "getColumnTypeName" -> at(meta.typeNames, args)
                    "getColumnType" -> Types.INTEGER + (args[0] as Int)
                    "isNullable" -> at(meta.nullable, args)
                    "isAutoIncrement" -> at(meta.autoIncrement, args)
                    "isWrapperFor" -> meta.pg != null && args[0] == PGResultSetMetaData::class.java
                    "unwrap" -> self
                    "getBaseSchemaName" -> at(checkNotNull(meta.pg).first, args)
                    "getBaseTableName" -> at(checkNotNull(meta.pg).second, args)
                    "getBaseColumnName" -> at(checkNotNull(meta.pg).third, args)
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as ResultSetMetaData
        return self
    }

    private fun columnsOf(meta: Meta): List<ColumnInfo> {
        val resultSet = fake<ResultSet> { m, _ -> if (m == "getMetaData") metaData(meta) else null }
        return JdbcValues.columns(resultSet)
    }

    private fun column(
        typeName: String,
        jdbcType: Int,
    ) = ColumnInfo("c", "c", typeName, jdbcType, null, null, nullable = true, autoIncrement = false)

    @Test
    fun `columns of a plain driver take schema, else catalog, and name the column and its label`() {
        val columns =
            columnsOf(
                Meta(
                    names = listOf("id", "n", "x"),
                    labels = listOf("ID", " ", null),
                    schemas = listOf("public", " ", null),
                    catalogs = listOf("db1", "db2", "db3"),
                    tables = listOf("t1", " ", null),
                    typeNames = listOf("INT", null, "VARCHAR"),
                    nullable =
                        listOf(
                            ResultSetMetaData.columnNoNulls,
                            ResultSetMetaData.columnNullable,
                            ResultSetMetaData.columnNullableUnknown,
                        ),
                    autoIncrement = listOf(true, false, true),
                ),
            )

        assertEquals(
            listOf(
                ColumnInfo(
                    "id",
                    "ID",
                    "INT",
                    Types.INTEGER + 1,
                    "public",
                    "t1",
                    nullable = false,
                    autoIncrement = true,
                ),
                ColumnInfo("n", "n", "", Types.INTEGER + 2, "db2", null, nullable = true, autoIncrement = false),
                ColumnInfo("x", "x", "VARCHAR", Types.INTEGER + 3, "db3", null, nullable = true, autoIncrement = true),
            ),
            columns,
        )
    }

    @Test
    fun `columns of PostgreSQL name their base schema, table and column, not the alias`() {
        val columns =
            columnsOf(
                Meta(
                    names = listOf("alias", "other"),
                    labels = listOf("Shown", "other"),
                    schemas = listOf("not-this", "not-this"),
                    catalogs = listOf("not-this", "not-this"),
                    tables = listOf("not-this", "not-this"),
                    pg = Triple(listOf("public", ""), listOf("users", null), listOf("login", " ")),
                ),
            )

        assertEquals(
            listOf(
                ColumnInfo("login", "Shown", "int4", Types.INTEGER + 1, "public", "users", true, false),
                ColumnInfo("other", "other", "int4", Types.INTEGER + 2, null, null, true, false),
            ),
            columns,
        )
    }

    @ParameterizedTest
    @CsvSource(
        "timestamptz, 12, OffsetDateTime",
        "TIMESTAMPTZ, 12, OffsetDateTime",
        "timestamptz, 92, OffsetDateTime",
        "other, 2014, OffsetDateTime",
        "timetz, 12, OffsetTime",
        "timetz, 93, OffsetTime",
        "other, 2013, OffsetTime",
        "other, 93, LocalDateTime",
        "other, 92, LocalTime",
        "other, 91, LocalDate",
        "year, 91, none",
        "YEAR, 91, none",
        "other, 12, none",
    )
    fun `a temporal column is read as the java time class of its type name or JDBC type`(
        typeName: String,
        jdbcType: Int,
        expected: String,
    ) {
        val actual = JdbcValues.temporalClass(column(typeName, jdbcType))
        assertEquals(expected, actual?.simpleName ?: "none")
    }

    private fun rowOf(
        column: ColumnInfo,
        onObject: (List<Any?>) -> Any?,
        onString: () -> String? = { "as text" },
    ): Any? =
        JdbcValues
            .row(
                fake<ResultSet> { m, args ->
                    assertEquals(1, args[0])
                    when (m) {
                        "getObject" -> onObject(args.drop(1))
                        "getString" -> onString()
                        else -> throw UnsupportedOperationException(m)
                    }
                },
                listOf(column),
            ).single()

    @Test
    fun `a temporal column is asked for its java time class, others for a plain object`() {
        val asked = mutableListOf<List<Any?>>()
        val date = LocalDate.of(2026, 1, 2)

        assertEquals(
            date,
            rowOf(column("date", Types.DATE), {
                asked.add(it)
                date
            }),
        )
        assertEquals(
            5,
            rowOf(column("int4", Types.INTEGER), {
                asked.add(it)
                5
            }),
        )

        assertEquals(listOf(listOf<Any?>(LocalDate::class.java), emptyList()), asked)
    }

    @Test
    fun `a value the driver cannot map is shown as the server sends it`() {
        val failing: (List<Any?>) -> Any? = { throw SQLException("Bad value for type", "22003") }

        assertEquals("as text", rowOf(column("money", Types.DOUBLE), failing))
        assertEquals("as text", rowOf(column("date", Types.DATE), failing))
    }

    @Test
    fun `a driver object that formats itself is replaced by the server's text`() {
        assertEquals("1 day", rowOf(column("interval", Types.OTHER), { PGobject() }, { "1 day" }))
    }

    @Test
    fun `dates and times of java sql become java time`() {
        assertEquals(LocalDate.of(2026, 3, 4), JdbcValues.plain(java.sql.Date.valueOf("2026-03-04")))
        assertEquals(LocalTime.of(5, 6, 7), JdbcValues.plain(java.sql.Time.valueOf("05:06:07")))
        assertEquals(OffsetTime.MAX, JdbcValues.plain(OffsetTime.MAX))
    }

    @Test
    fun `primitive arrays and nested arrays become lists`() {
        assertEquals(listOf(1L, 2L), JdbcValues.plain(longArrayOf(1, 2)))
        assertEquals(listOf<Short>(3), JdbcValues.plain(shortArrayOf(3)))
        assertEquals(listOf(1.5), JdbcValues.plain(doubleArrayOf(1.5)))
        assertEquals(listOf(2.5f), JdbcValues.plain(floatArrayOf(2.5f)))
        assertEquals(listOf(true, false), JdbcValues.plain(booleanArrayOf(true, false)))
        assertEquals(listOf(listOf(1, 2), null), JdbcValues.plain(arrayOf(arrayOf(1, 2), null)))
        assertEquals("abc", JdbcValues.plain(charArrayOf('a', 'b', 'c')))
    }

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
