package dev.sluide.databases.meta

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals

class MariaTreeTest {
    private fun kc(
        name: String,
        col: String,
        ord: Int,
        rtable: String? = null,
        rcol: String? = null,
    ) = mapOf(
        "tbl" to "order",
        "name" to name,
        "col" to col,
        "ord" to ord,
        "rschema" to rtable?.let { "shop" },
        "rtable" to rtable,
        "rcol" to rcol,
    )

    private val rows =
        MariaSchemaRows(
            tables =
                listOf(
                    mapOf("name" to "order", "kind" to "BASE TABLE"),
                    mapOf("name" to "hist", "kind" to "SYSTEM VERSIONED"),
                    mapOf("name" to "v", "kind" to "VIEW"),
                    mapOf("name" to "s", "kind" to "SEQUENCE"),
                ),
            columns =
                listOf(
                    mapOf(
                        "tbl" to "order",
                        "name" to "id",
                        "type" to "int(11)",
                        "nullable" to "NO",
                        "dflt" to null,
                        "extra" to "auto_increment",
                    ),
                    mapOf(
                        "tbl" to "order",
                        "name" to "qty",
                        "type" to "int(11)",
                        "nullable" to "YES",
                        "dflt" to "0",
                        "extra" to "",
                    ),
                ),
            constraints =
                listOf(
                    mapOf("tbl" to "order", "name" to "PRIMARY", "type" to "PRIMARY KEY"),
                    mapOf("tbl" to "order", "name" to "fk_c", "type" to "FOREIGN KEY"),
                ),
            keyColumns =
                listOf(
                    kc("PRIMARY", "id", 1),
                    kc("fk_c", "a", 1, "customer", "x"),
                    kc("fk_c", "b", 2, "customer", "y"),
                ),
            referential =
                listOf(
                    mapOf(
                        "tbl" to "order",
                        "name" to "fk_c",
                        "onupdate" to "RESTRICT",
                        "ondelete" to "CASCADE",
                    ),
                ),
            checks = listOf(mapOf("tbl" to "order", "name" to "qty_ok", "clause" to "`qty` >= 0")),
            statistics =
                listOf(
                    mapOf("tbl" to "order", "name" to "idx_ab", "nonunique" to 0, "col" to "b", "seq" to 2),
                    mapOf("tbl" to "order", "name" to "idx_ab", "nonunique" to 0, "col" to "a", "seq" to 1),
                    mapOf("tbl" to "order", "name" to "idx_q", "nonunique" to 1, "col" to "qty", "seq" to 1),
                ),
            triggers = listOf(mapOf("tbl" to "order", "name" to "trg", "timing" to "BEFORE", "event" to "INSERT")),
            routines =
                listOf(
                    mapOf("name" to "f", "kind" to "FUNCTION", "result" to "int(11)"),
                    mapOf("name" to "p", "kind" to "PROCEDURE", "result" to null),
                ),
            parameters =
                listOf(
                    mapOf("routine" to "f", "ord" to 1, "type" to "int(11)"),
                    mapOf(
                        "routine" to "f",
                        "ord" to 2,
                        "type" to "text",
                    ),
                ),
            events = listOf(mapOf("name" to "nightly", "status" to "ENABLED")),
        )

    private val tree = mariaSchemaTree("shop", rows)

    @Test
    fun `groups skip materialized views and types and end with events`() {
        assertEquals(listOf("tables", "views", "routines", "sequences", "events"), tree.map { it.name })
        assertEquals(listOf("hist", "order"), tree.first().children.map { it.name })
    }

    @Test
    fun `table children and their details`() {
        val order = tree.first().children.single { it.name == "order" }
        assertEquals(
            listOf("id", "qty", "keys", "foreign keys", "indexes", "checks", "triggers"),
            order.children.map {
                it.name
            },
        )
        assertEquals("int(11) not null auto_increment", order.children[0].detail)
        assertEquals("int(11) default 0", order.children[1].detail)
        val fk =
            order.children
                .single { it.name == "foreign keys" }
                .children
                .single()
        assertEquals("(a, b) → shop.customer(x, y) on delete cascade", fk.detail)
        val indexes = order.children.single { it.name == "indexes" }.children
        assertEquals(listOf("(a, b) unique", "(qty)"), indexes.map { it.detail })
        assertEquals(
            "before insert",
            order.children
                .last()
                .children
                .single()
                .detail,
        )
    }

    @Test
    fun `routines show parameter types and result`() {
        val routines = tree.single { it.name == "routines" }.children
        assertEquals(listOf("(int(11), text) → int(11)", "()"), routines.map { it.detail })
        assertEquals(ObjectKind.PROCEDURE, routines[1].kind)
    }

    @ParameterizedTest
    @CsvSource(
        "TABLE,order,,SHOW CREATE TABLE `shop`.`order`,Create Table",
        "VIEW,v,,SHOW CREATE VIEW `shop`.`v`,Create View",
        "FUNCTION,f,,SHOW CREATE FUNCTION `shop`.`f`,Create Function",
        "PROCEDURE,p,,SHOW CREATE PROCEDURE `shop`.`p`,Create Procedure",
        "TRIGGER,trg,order,SHOW CREATE TRIGGER `shop`.`trg`,SQL Original Statement",
        "EVENT,nightly,,SHOW CREATE EVENT `shop`.`nightly`,Create Event",
        "SEQUENCE,s,,SHOW CREATE SEQUENCE `shop`.`s`,Create Table",
        "COLUMN,qty,order,SHOW CREATE TABLE `shop`.`order`,Create Table",
        "INDEX,idx_q,order,SHOW CREATE TABLE `shop`.`order`,Create Table",
        "CHECK,qty_ok,order,SHOW CREATE TABLE `shop`.`order`,Create Table",
    )
    fun `each object uses the matching show create`(
        kind: ObjectKind,
        name: String,
        table: String?,
        sql: String,
        column: String,
    ) {
        assertEquals(ShowCreate(sql, column), mariaShowCreate(DbObject(kind, "shop", name, table)))
    }
}
