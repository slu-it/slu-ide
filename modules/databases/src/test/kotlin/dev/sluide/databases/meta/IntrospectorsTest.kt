package dev.sluide.databases.meta

import dev.sluide.databases.connect.DatabaseKind
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** The introspectors' queries and what they do with the answers, on [CatalogJdbc] (no server; see its KDoc). */
class IntrospectorsTest {
    private val jdbc = CatalogJdbc()
    private val c = jdbc.connection

    private fun called(sql: String) = jdbc.calls.filter { it.first == sql }.map { it.second }

    @Test
    fun `the introspector of a kind`() {
        assertSame(PostgresIntrospector, Introspector.of(DatabaseKind.POSTGRESQL))
        assertSame(MariaDbIntrospector, Introspector.of(DatabaseKind.MARIADB))
    }

    // ---- PostgreSQL

    @Test
    fun `postgresql schemas are sorted and hide the system ones unless asked`() {
        jdbc.answer(PgQueries.SCHEMAS, mapOf("name" to "zeta"), mapOf("name" to "pg_catalog"), mapOf("name" to "alpha"))

        assertEquals(listOf("alpha", "zeta"), PostgresIntrospector.schemas(c, false))
        assertEquals(listOf("alpha", "pg_catalog", "zeta"), PostgresIntrospector.schemas(c, true))
    }

    @Test
    fun `postgresql schema reads every row set for the schema and the routines and types`() {
        jdbc.answer(PgQueries.RELATIONS, mapOf("name" to "t", "kind" to "r"))
        jdbc.answer(PgQueries.COLUMNS, mapOf("tbl" to "t", "name" to "id", "type" to "integer", "notnull" to true))
        jdbc.answer(
            PgQueries.ROUTINES,
            mapOf(
                "name" to "f",
                "kind" to "f",
                "types" to "",
                "result" to "int",
                "ident" to "i",
            ),
        )
        jdbc.answer(PgQueries.TYPES, mapOf("name" to "mood", "kind" to "e", "detail" to "enum"))

        val tree = PostgresIntrospector.schema(c, "s")

        assertEquals(listOf("tables", "routines", "types"), tree.map { it.name })
        assertEquals(
            "integer not null",
            tree[0]
                .children
                .single()
                .children
                .single()
                .detail,
        )
        assertEquals("() → int", tree[1].children.single().detail)
        assertEquals("enum", tree[2].children.single().detail)
        for (sql in listOf(
            PgQueries.RELATIONS,
            PgQueries.COLUMNS,
            PgQueries.CONSTRAINTS,
            PgQueries.INDEXES,
            PgQueries.TRIGGERS,
        )) {
            assertEquals(listOf(listOf<Any?>("s", null, null)), called(sql), sql)
        }
        assertEquals(listOf(listOf<Any?>("s")), called(PgQueries.ROUTINES))
        assertEquals(listOf(listOf<Any?>("s")), called(PgQueries.TYPES))
        assertEquals(emptyList(), called(PgQueries.OWNED_SEQUENCES))
    }

    @Test
    fun `postgresql table ddl reads the rows of that table and its owned sequences`() {
        jdbc.answer(PgQueries.RELATIONS, mapOf("name" to "t", "kind" to "r"))
        jdbc.answer(PgQueries.COLUMNS, mapOf("tbl" to "t", "name" to "id", "type" to "integer"))

        val ddl = PostgresIntrospector.ddl(c, DbObject(ObjectKind.TABLE, "s", "t"))

        assertEquals("CREATE TABLE \"s\".\"t\" (\n    \"id\" integer\n);\n", ddl)
        for (sql in listOf(
            PgQueries.RELATIONS,
            PgQueries.CONSTRAINTS,
            PgQueries.INDEXES,
            PgQueries.TRIGGERS,
            PgQueries.OWNED_SEQUENCES,
        )) {
            assertEquals(listOf(listOf<Any?>("s", "t", "t")), called(sql), sql)
        }
    }

    @Test
    fun `postgresql view ddl uses the definition and a missing view gives nothing`() {
        val view = DbObject(ObjectKind.VIEW, "s", "v")
        assertEquals("", PostgresIntrospector.ddl(c, view))

        jdbc.answer(PgQueries.VIEW_DEF, mapOf("def" to "SELECT 1"))
        assertEquals("CREATE OR REPLACE VIEW \"s\".\"v\" AS\nSELECT 1;\n", PostgresIntrospector.ddl(c, view))
        assertEquals(listOf(listOf<Any?>("s", "v"), listOf("s", "v")), called(PgQueries.VIEW_DEF))
        assertEquals(emptyList(), called(PgQueries.INDEXES))
    }

    @Test
    fun `postgresql materialized view ddl lists its indexes`() {
        jdbc.answer(PgQueries.VIEW_DEF, mapOf("def" to "SELECT 1"))
        jdbc.answer(PgQueries.INDEXES, mapOf("tbl" to "m", "name" to "i", "backs" to false, "def" to "CREATE INDEX i"))

        val ddl = PostgresIntrospector.ddl(c, DbObject(ObjectKind.MATERIALIZED_VIEW, "s", "m"))

        assertEquals("CREATE MATERIALIZED VIEW \"s\".\"m\" AS\nSELECT 1\nWITH DATA;\n\nCREATE INDEX i;\n", ddl)
        assertEquals(listOf(listOf<Any?>("s", "m", "m")), called(PgQueries.INDEXES))
    }

    @ParameterizedTest
    @ValueSource(strings = ["FUNCTION", "PROCEDURE"])
    fun `postgresql routine ddl is the definition with a semicolon and nothing when gone`(kind: ObjectKind) {
        val routine = DbObject(kind, "s", "f", ref = "integer")
        assertEquals("", PostgresIntrospector.ddl(c, routine))

        jdbc.answer(PgQueries.FUNCTION_DEF, mapOf("def" to "CREATE FUNCTION f() ...;  "))

        assertEquals("CREATE FUNCTION f() ...;\n", PostgresIntrospector.ddl(c, routine))
        assertEquals(
            listOf(listOf<Any?>("s", "f", "integer"), listOf("s", "f", "integer")),
            called(PgQueries.FUNCTION_DEF),
        )
    }

    @Test
    fun `postgresql sequence ddl`() {
        val sequence = DbObject(ObjectKind.SEQUENCE, "s", "q")
        assertEquals("", PostgresIntrospector.ddl(c, sequence))

        jdbc.answer(
            PgQueries.SEQUENCE_DEF,
            mapOf(
                "type" to "integer",
                "start_value" to 1,
                "increment_by" to 1,
                "min_value" to 1,
                "max_value" to 9,
                "cache_size" to 1,
                "cycle" to false,
            ),
        )

        assertEquals(
            "CREATE SEQUENCE \"s\".\"q\"\n    AS integer\n    START WITH 1\n    INCREMENT BY 1\n    MINVALUE 1\n" +
                "    MAXVALUE 9\n    CACHE 1\n    NO CYCLE;\n",
            PostgresIntrospector.ddl(c, sequence),
        )
        assertEquals(listOf(listOf<Any?>("s", "q"), listOf("s", "q")), called(PgQueries.SEQUENCE_DEF))
    }

    @Test
    fun `postgresql enum, domain and composite ddl`() {
        jdbc.answer(PgQueries.ENUM_LABELS, mapOf("label" to "'a'"), mapOf("label" to "'b'"))
        jdbc.answer(PgQueries.DOMAIN_DEF, mapOf("base" to "integer", "notnull" to true))
        jdbc.answer(PgQueries.DOMAIN_CONSTRAINTS, mapOf("name" to "k", "def" to "CHECK (VALUE > 0)"))
        jdbc.answer(PgQueries.COMPOSITE_ATTRIBUTES, mapOf("name" to "a", "type" to "integer"))

        assertEquals(
            "CREATE TYPE \"s\".\"e\" AS ENUM ('a', 'b');\n",
            PostgresIntrospector.ddl(c, DbObject(ObjectKind.ENUM, "s", "e")),
        )
        assertEquals(
            "CREATE DOMAIN \"s\".\"d\" AS integer\n    NOT NULL\n    CONSTRAINT \"k\" CHECK (VALUE > 0);\n",
            PostgresIntrospector.ddl(c, DbObject(ObjectKind.DOMAIN, "s", "d")),
        )
        assertEquals(
            "CREATE TYPE \"s\".\"t\" AS (\n    \"a\" integer\n);\n",
            PostgresIntrospector.ddl(c, DbObject(ObjectKind.COMPOSITE_TYPE, "s", "t")),
        )
        assertEquals(listOf(listOf<Any?>("s", "e")), called(PgQueries.ENUM_LABELS))
        assertEquals(listOf(listOf<Any?>("s", "d")), called(PgQueries.DOMAIN_DEF))
        assertEquals(listOf(listOf<Any?>("s", "d")), called(PgQueries.DOMAIN_CONSTRAINTS))
        assertEquals(listOf(listOf<Any?>("s", "t")), called(PgQueries.COMPOSITE_ATTRIBUTES))
    }

    @Test
    fun `postgresql child ddl reads the table's rows and an object without a table gives nothing`() {
        jdbc.answer(PgQueries.COLUMNS, mapOf("tbl" to "t", "name" to "a", "type" to "text"))

        assertEquals(
            "ALTER TABLE \"s\".\"t\" ADD COLUMN \"a\" text;\n",
            PostgresIntrospector.ddl(c, DbObject(ObjectKind.COLUMN, "s", "a", "t")),
        )
        assertEquals(listOf(listOf<Any?>("s", "t", "t")), called(PgQueries.COLUMNS))
        assertEquals("", PostgresIntrospector.ddl(c, DbObject(ObjectKind.COLUMN, "s", "a")))
        assertEquals("", PostgresIntrospector.ddl(c, DbObject(ObjectKind.COLUMN, "s", "gone", "t")))
        assertEquals("", PostgresIntrospector.ddl(c, DbObject(ObjectKind.GROUP, "s", "keys", "t")))
    }

    @Test
    fun `postgresql primary key columns`() {
        jdbc.answer(PgQueries.PRIMARY_KEY, mapOf("name" to "a"), mapOf("name" to "b"))

        assertEquals(listOf("a", "b"), PostgresIntrospector.primaryKey(c, "s", "t"))
        assertEquals(listOf(listOf<Any?>("s", "t")), called(PgQueries.PRIMARY_KEY))
    }

    // ---- MariaDB

    @Test
    fun `mariadb schemas are sorted and hide the system ones unless asked`() {
        jdbc.answer(MariaQueries.SCHEMAS, mapOf("name" to "shop"), mapOf("name" to "mysql"), mapOf("name" to "app"))

        assertEquals(listOf("app", "shop"), MariaDbIntrospector.schemas(c, false))
        assertEquals(listOf("app", "mysql", "shop"), MariaDbIntrospector.schemas(c, true))
    }

    @Test
    fun `mariadb schema reads every row set for the database`() {
        jdbc.answer(MariaQueries.TABLES, mapOf("name" to "t", "kind" to "BASE TABLE"))
        jdbc.answer(MariaQueries.COLUMNS, mapOf("tbl" to "t", "name" to "id", "type" to "int", "nullable" to "NO"))
        jdbc.answer(MariaQueries.CONSTRAINTS, mapOf("tbl" to "t", "name" to "PRIMARY", "type" to "PRIMARY KEY"))
        jdbc.answer(MariaQueries.KEY_COLUMNS, mapOf("tbl" to "t", "name" to "PRIMARY", "col" to "id", "ord" to 1))
        jdbc.answer(MariaQueries.CHECKS, mapOf("tbl" to "t", "name" to "ck", "clause" to "id > 0"))
        jdbc.answer(
            MariaQueries.STATISTICS,
            mapOf(
                "tbl" to "t",
                "name" to "ix",
                "nonunique" to 1,
                "col" to "id",
                "seq" to 1,
            ),
        )
        jdbc.answer(
            MariaQueries.TRIGGERS,
            mapOf("tbl" to "t", "name" to "tg", "timing" to "AFTER", "event" to "UPDATE"),
        )
        jdbc.answer(MariaQueries.ROUTINES, mapOf("name" to "f", "kind" to "FUNCTION", "result" to "int"))
        jdbc.answer(
            MariaQueries.PARAMETERS,
            mapOf("routine" to "f", "rtype" to "FUNCTION", "ord" to 1, "type" to "text"),
        )
        jdbc.answer(MariaQueries.EVENTS, mapOf("name" to "ev", "status" to "ENABLED"))

        val tree = MariaDbIntrospector.schema(c, "shop")

        assertEquals(listOf("tables", "routines", "events"), tree.map { it.name })
        val table = tree[0].children.single()
        assertEquals(
            listOf("id", "keys", "indexes", "checks", "triggers"),
            table.children.map { it.name },
        )
        assertEquals(
            "(id)",
            table.children[1]
                .children
                .single()
                .detail,
        )
        assertEquals("(text) → int", tree[1].children.single().detail)
        assertEquals("enabled", tree[2].children.single().detail)
        for (sql in jdbc.results.keys + listOf(MariaQueries.REFERENTIAL)) {
            assertEquals(listOf(listOf<Any?>("shop")), called(sql), sql)
        }
    }

    private val mariaTable = DbObject(ObjectKind.TABLE, "shop", "t")

    @Test
    fun `mariadb ddl runs the show create statement and ends it with a semicolon`() {
        jdbc.results["SHOW CREATE TABLE `shop`.`t`"] =
            CatalogJdbc.Result(listOf("Table", "Create Table"), listOf(listOf("t", "CREATE TABLE t (a int);  ")))

        assertEquals("CREATE TABLE t (a int);\n", MariaDbIntrospector.ddl(c, mariaTable))
        assertEquals(listOf("SHOW CREATE TABLE `shop`.`t`" to emptyList<Any?>()), jdbc.calls)
        assertEquals(2, jdbc.closed)
    }

    @Test
    fun `mariadb ddl of a column of a view uses the create label that is there`() {
        jdbc.results["SHOW CREATE TABLE `shop`.`v`"] =
            CatalogJdbc.Result(
                listOf("View", "Create View", "character_set_client"),
                listOf(listOf("v", "CREATE VIEW v", "utf8")),
            )

        assertEquals("CREATE VIEW v;\n", MariaDbIntrospector.ddl(c, DbObject(ObjectKind.COLUMN, "shop", "a", "v")))
    }

    @Test
    fun `mariadb ddl is empty without a create label, without a row or with a null value`() {
        val sql = "SHOW CREATE TABLE `shop`.`t`"

        jdbc.results[sql] = CatalogJdbc.Result(listOf("Table", "Other"), listOf(listOf("t", "x")))
        assertEquals("", MariaDbIntrospector.ddl(c, mariaTable))
        jdbc.results[sql] = CatalogJdbc.Result(listOf("Table", "Create Table"), emptyList())
        assertEquals("", MariaDbIntrospector.ddl(c, mariaTable))
        jdbc.results[sql] = CatalogJdbc.Result(listOf("Table", "Create Table"), listOf(listOf("t", null)))
        assertEquals("", MariaDbIntrospector.ddl(c, mariaTable))
    }

    @ParameterizedTest
    @ValueSource(ints = [1146, 1305, 1360, 1539])
    fun `mariadb ddl of an object dropped since the tree was loaded is empty`(code: Int) {
        jdbc.failures["SHOW CREATE TABLE `shop`.`t`"] = SQLException("gone", "42S02", code)

        assertEquals("", MariaDbIntrospector.ddl(c, mariaTable))
    }

    @Test
    fun `mariadb ddl passes on any other sql error`() {
        val error = SQLException("syntax", "42000", 1064)
        jdbc.failures["SHOW CREATE TABLE `shop`.`t`"] = error

        assertSame(error, assertFailsWith<SQLException> { MariaDbIntrospector.ddl(c, mariaTable) })
    }

    @Test
    fun `mariadb primary key columns`() {
        jdbc.answer(MariaQueries.PRIMARY_KEY, mapOf("name" to "a"), mapOf("name" to "b"))

        assertEquals(listOf("a", "b"), MariaDbIntrospector.primaryKey(c, "shop", "t"))
        assertEquals(listOf(listOf<Any?>("shop", "t")), called(MariaQueries.PRIMARY_KEY))
    }

    @Test
    fun `postgresql ddl of a table without a schema asks with an empty schema`() {
        PostgresIntrospector.ddl(c, DbObject(ObjectKind.TABLE, null, "t"))

        assertEquals(listOf(listOf<Any?>("", "t", "t")), called(PgQueries.RELATIONS))
    }

    @Test
    fun `postgresql routine ddl without a definition text is empty`() {
        jdbc.answer(PgQueries.FUNCTION_DEF, mapOf("def" to null))

        assertEquals("", PostgresIntrospector.ddl(c, DbObject(ObjectKind.FUNCTION, "s", "f", ref = "i")))
    }

    @Test
    fun `postgresql child ddl without a table asks nothing`() {
        PostgresIntrospector.ddl(c, DbObject(ObjectKind.COLUMN, "s", "a"))

        assertEquals(emptyList(), jdbc.calls)
    }

    @Test
    fun `mariadb ddl of a trigger reads the column that does not start with Create`() {
        jdbc.results["SHOW CREATE TRIGGER `shop`.`tg`"] =
            CatalogJdbc.Result(
                listOf("Trigger", "sql_mode", "SQL Original Statement", "Created"),
                listOf(listOf("tg", "", "CREATE TRIGGER tg ...", "2024-01-01")),
            )

        assertEquals(
            "CREATE TRIGGER tg ...;\n",
            MariaDbIntrospector.ddl(c, DbObject(ObjectKind.TRIGGER, "shop", "tg", "t")),
        )
    }
}
