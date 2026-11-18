package dev.sluide.databases.session

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.postgresql.PGResultSetMetaData
import org.postgresql.util.PGobject
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Types
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** The columns and the row values [JdbcValues] reads from fake JDBC objects (`plain()` is in JdbcValuesTest). */
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
}
