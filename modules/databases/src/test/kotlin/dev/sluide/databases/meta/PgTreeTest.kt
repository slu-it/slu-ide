package dev.sluide.databases.meta

import dev.sluide.databases.connect.DatabaseKind
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals

class PgTreeTest {
    private fun rel(
        name: String,
        kind: String,
    ) = mapOf("name" to name, "kind" to kind)

    private fun col(
        tbl: String,
        name: String,
        type: String,
        ordinal: Int,
        notnull: Boolean = false,
        dflt: String? = null,
    ) = mapOf("tbl" to tbl, "name" to name, "type" to type, "notnull" to notnull, "dflt" to dflt, "ordinal" to ordinal)

    private val rows =
        PgSchemaRows(
            relations =
                listOf(
                    rel("order", "r"),
                    rel("customer", "p"),
                    rel("v_open", "v"),
                    rel("mv_sum", "m"),
                    rel("seq1", "S"),
                    rel("remote", "f"),
                ),
            columns =
                listOf(
                    col("order", "id", "integer", 1, notnull = true, dflt = "0"),
                    col("order", "customer_id", "integer", 2),
                    col("order", "a_first_by_name", "text", 3),
                    col("v_open", "id", "integer", 1),
                ),
            constraints =
                listOf(
                    mapOf("tbl" to "order", "name" to "order_pkey", "type" to "p", "cols" to "id"),
                    mapOf("tbl" to "order", "name" to "a_uniq", "type" to "u", "cols" to "a, b"),
                    mapOf(
                        "tbl" to "order",
                        "name" to "order_customer_fk",
                        "type" to "f",
                        "cols" to "customer_id",
                        "fschema" to "public",
                        "ftable" to "customer",
                        "fcols" to "id",
                    ),
                    mapOf("tbl" to "order", "name" to "positive", "type" to "c", "def" to "CHECK ((id > 0))"),
                ),
            indexes =
                listOf(
                    mapOf("tbl" to "order", "name" to "order_idx", "uniq" to true, "cols" to "a, b"),
                    mapOf("tbl" to "order", "name" to "plain_idx", "uniq" to false, "cols" to "c"),
                ),
            triggers =
                listOf(
                    mapOf(
                        "tbl" to "order",
                        "name" to "trg",
                        "def" to
                            "CREATE TRIGGER trg BEFORE INSERT OR UPDATE ON public.\"order\" FOR EACH ROW",
                    ),
                ),
            routines =
                listOf(
                    mapOf(
                        "name" to "add",
                        "kind" to "f",
                        "ident" to "b integer, a text",
                        "types" to "integer, text",
                        "result" to "integer",
                    ),
                    mapOf(
                        "name" to "add",
                        "kind" to "f",
                        "ident" to "a integer",
                        "types" to "integer",
                        "result" to "integer",
                    ),
                    mapOf("name" to "run", "kind" to "p", "ident" to "", "types" to "", "result" to null),
                ),
            types =
                listOf(
                    mapOf("name" to "mood", "kind" to "e", "detail" to "sad, happy"),
                    mapOf("name" to "pos", "kind" to "d", "detail" to "integer"),
                    mapOf("name" to "pair", "kind" to "c", "detail" to ""),
                ),
        )

    private val tree = pgSchemaTree("public", rows)

    private fun group(name: String) = tree.single { it.name == name }

    @Test
    fun `groups come in the documented order and empty ones are left out`() {
        assertEquals(
            listOf("tables", "views", "materialized views", "routines", "sequences", "types"),
            tree.map { it.name },
        )
        assertEquals(emptyList(), pgSchemaTree("empty", PgSchemaRows()))
    }

    @Test
    fun `table kinds table partitioned and foreign all count as tables`() {
        assertEquals(listOf("customer", "order", "remote"), group("tables").children.map { it.name })
        assert(group("tables").children.all { it.kind == ObjectKind.TABLE })
    }

    @Test
    fun `table children are columns in ordinal order then the non-empty subgroups`() {
        val order = group("tables").children.single { it.name == "order" }
        assertEquals(
            listOf("id", "customer_id", "a_first_by_name", "keys", "foreign keys", "indexes", "checks", "triggers"),
            order.children.map { it.name },
        )
        val keys = order.children.single { it.name == "keys" }
        assertEquals(listOf(ObjectKind.PRIMARY_KEY, ObjectKind.UNIQUE), keys.children.map { it.kind })
        assert(
            group("tables")
                .children
                .single { it.name == "customer" }
                .children
                .isEmpty(),
        )
    }

    @Test
    fun `views have only columns`() {
        val view = group("views").children.single()
        assertEquals(listOf("id"), view.children.map { it.name })
        assertEquals(ObjectKind.MATERIALIZED_VIEW, group("materialized views").children.single().kind)
    }

    @Test
    fun `details of column foreign key index trigger and type`() {
        val order = group("tables").children.single { it.name == "order" }

        fun child(
            group: String,
            name: String,
        ) = order.children
            .single { it.name == group }
            .children
            .single { it.name == name }
        assertEquals("integer not null default 0", order.children.first().detail)
        assertEquals("(customer_id) → public.customer(id)", child("foreign keys", "order_customer_fk").detail)
        assertEquals("(a, b) unique", child("indexes", "order_idx").detail)
        assertEquals("(c)", child("indexes", "plain_idx").detail)
        assertEquals("before insert or update", child("triggers", "trg").detail)
        assertEquals("(a, b)", child("keys", "a_uniq").detail)
        assertEquals("sad, happy", group("types").children.single { it.name == "mood" }.detail)
        assertEquals(
            listOf(ObjectKind.ENUM, ObjectKind.COMPOSITE_TYPE, ObjectKind.DOMAIN),
            group("types").children.map { it.kind },
        )
    }

    @Test
    fun `overloads are kept apart by their identity arguments`() {
        val routines = group("routines").children
        assertEquals(listOf("a integer", "b integer, a text", ""), routines.map { it.ref })
        assertEquals("(integer, text) → integer", routines[1].detail)
        assertEquals("()", routines[2].detail)
        assertEquals(ObjectKind.PROCEDURE, routines[2].kind)
    }

    @ParameterizedTest
    @CsvSource("false,public;sales", "true,information_schema;pg_catalog;pg_temp_3;pg_toast;public;sales")
    fun `system schemas are hidden unless asked for`(
        includeSystem: Boolean,
        expected: String,
    ) {
        val names = listOf("sales", "pg_toast", "public", "pg_temp_3", "pg_catalog", "information_schema")
        assertEquals(expected.split(";"), filterSchemas(DatabaseKind.POSTGRESQL, names, includeSystem))
    }
}
