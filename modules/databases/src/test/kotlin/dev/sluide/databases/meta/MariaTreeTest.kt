package dev.sluide.databases.meta

import dev.sluide.testsupport.collidingWith
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
                    mapOf("tbl" to "order", "name" to "note", "type" to "text", "nullable" to "YES", "dflt" to "NULL"),
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
                    mapOf("name" to "f", "kind" to "PROCEDURE", "result" to null),
                ),
            parameters =
                listOf(
                    mapOf("routine" to "f", "rtype" to "FUNCTION", "ord" to 1, "type" to "int(11)"),
                    mapOf("routine" to "f", "rtype" to "FUNCTION", "ord" to 2, "type" to "text"),
                    mapOf("routine" to "f", "rtype" to "PROCEDURE", "ord" to 1, "type" to "varchar(5)"),
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
            listOf("id", "qty", "note", "keys", "foreign keys", "indexes", "checks", "triggers"),
            order.children.map {
                it.name
            },
        )
        assertEquals("int(11) not null auto_increment", order.children[0].detail)
        assertEquals("int(11) default 0", order.children[1].detail)
        assertEquals("text", order.children[2].detail)
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
        assertEquals(listOf("(int(11), text) → int(11)", "(varchar(5))", "()"), routines.map { it.detail })
        assertEquals(
            listOf(ObjectKind.FUNCTION, ObjectKind.PROCEDURE, ObjectKind.PROCEDURE),
            routines.map { it.kind },
        )
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

    private fun keysOf(
        constraints: List<Row>,
        keyColumns: List<Row>,
        referential: List<Row> = emptyList(),
    ) = mariaSchemaTree(
        "shop",
        MariaSchemaRows(
            tables = listOf(mapOf("name" to "order", "kind" to "BASE TABLE")),
            constraints = constraints,
            keyColumns = keyColumns,
            referential = referential,
        ),
    ).single()
        .children
        .single()
        .children
        .single { it.name == "keys" || it.name == "foreign keys" }
        .children

    @Test
    fun `a unique constraint is a key with its columns in key order`() {
        val keys =
            keysOf(
                constraints = listOf(mapOf("tbl" to "order", "name" to "uq", "type" to "UNIQUE")),
                keyColumns = listOf(kc("uq", "b", 2), kc("uq", "a", 1)),
            )

        assertEquals(listOf(ObjectKind.UNIQUE), keys.map { it.kind })
        assertEquals("(a, b)", keys.single().detail)
    }

    @Test
    fun `a primary key lists its columns in key order`() {
        val keys =
            keysOf(
                constraints = listOf(mapOf("tbl" to "order", "name" to "PRIMARY", "type" to "PRIMARY KEY")),
                keyColumns = listOf(kc("PRIMARY", "b", 2), kc("PRIMARY", "a", 1)),
            )

        assertEquals(ObjectKind.PRIMARY_KEY, keys.single().kind)
        assertEquals("(a, b)", keys.single().detail)
    }

    @ParameterizedTest
    @CsvSource(
        "CASCADE,' on delete cascade'",
        "SET NULL,' on delete set null'",
        "RESTRICT,''",
        "NO ACTION,''",
        "'',''",
    )
    fun `only a delete rule that is not the default is shown`(
        rule: String,
        shown: String,
    ) {
        val keys =
            keysOf(
                constraints = listOf(mapOf("tbl" to "order", "name" to "fk", "type" to "FOREIGN KEY")),
                keyColumns = listOf(kc("fk", "b", 2, "customer", "y"), kc("fk", "a", 1, "customer", "x")),
                referential = listOf(mapOf("tbl" to "order", "name" to "fk", "ondelete" to rule)),
            )

        assertEquals("(a, b) → shop.customer(x, y)$shown", keys.single().detail)
    }

    @Test
    fun `a foreign key without a referential row has no delete rule`() {
        val keys =
            keysOf(
                constraints = listOf(mapOf("tbl" to "order", "name" to "fk", "type" to "FOREIGN KEY")),
                keyColumns = listOf(kc("fk", "a", 1, "customer", "x")),
            )

        assertEquals("(a) → shop.customer(x)", keys.single().detail)
    }

    @Test
    fun `a foreign key without key columns is still listed`() {
        val keys = keysOf(listOf(mapOf("tbl" to "order", "name" to "fk", "type" to "FOREIGN KEY")), emptyList())

        assertEquals(listOf("fk"), keys.map { it.name })
        assertEquals(ObjectKind.FOREIGN_KEY, keys.single().kind)
    }

    @Test
    fun `an index orders its columns by position and shows an expression for a missing column`() {
        val tree =
            mariaSchemaTree(
                "shop",
                MariaSchemaRows(
                    tables = listOf(mapOf("name" to "order", "kind" to "BASE TABLE")),
                    statistics =
                        listOf(
                            mapOf("tbl" to "order", "name" to "i", "nonunique" to 1, "col" to "c", "seq" to 4),
                            mapOf("tbl" to "order", "name" to "i", "nonunique" to 1, "col" to "a", "seq" to 1),
                            mapOf("tbl" to "order", "name" to "i", "nonunique" to 1, "col" to "b", "seq" to 3),
                            mapOf("tbl" to "order", "name" to "i", "nonunique" to 1, "col" to null, "seq" to 2),
                        ),
                ),
            )

        val index =
            tree
                .single()
                .children
                .single()
                .children
                .single()
                .children
                .single()
        assertEquals("(a, (expression), b, c)", index.detail)
    }

    @Test
    fun `parameters are ordered by position and a procedure shows no result`() {
        val tree =
            mariaSchemaTree(
                "shop",
                MariaSchemaRows(
                    routines =
                        listOf(
                            mapOf("name" to "p", "kind" to "PROCEDURE", "result" to "int(11)"),
                            mapOf("name" to "f", "kind" to "FUNCTION", "result" to "text"),
                            mapOf("name" to "g", "kind" to "FUNCTION", "result" to " "),
                        ),
                    parameters =
                        listOf(
                            mapOf("routine" to "p", "rtype" to "PROCEDURE", "ord" to 2, "type" to "b"),
                            mapOf("routine" to "p", "rtype" to "PROCEDURE", "ord" to 1, "type" to "a"),
                            mapOf("routine" to "f", "rtype" to "FUNCTION", "ord" to 2, "type" to "d"),
                            mapOf("routine" to "f", "rtype" to "FUNCTION", "ord" to 1, "type" to "c"),
                        ),
                ),
            )

        assertEquals(listOf("(c, d) → text", "()", "(a, b)"), tree.single().children.map { it.detail })
    }

    @ParameterizedTest
    @CsvSource(
        "BASE TABLE,TABLE",
        "SYSTEM VERSIONED,TABLE",
        "SEQUENCE,SEQUENCE",
        "VIEW,VIEW",
        "SYSTEM VIEW,VIEW",
        "TEMPORARY,VIEW",
    )
    fun `table types map to object kinds`(
        type: String,
        expected: ObjectKind,
    ) {
        val tree = mariaSchemaTree("shop", MariaSchemaRows(tables = listOf(mapOf("name" to "x", "kind" to type))))

        assertEquals(
            expected.name,
            tree
                .single()
                .children
                .single()
                .kind.name,
        )
    }

    @ParameterizedTest
    @CsvSource(
        "int(11),NO,,,int(11) not null",
        "int(11),no,5,AUTO_INCREMENT,int(11) not null default 5 auto_increment",
        "text,YES,NULL,'  ',text",
        "text,,,,text",
    )
    fun `column detail`(
        type: String,
        nullable: String?,
        default: String?,
        extra: String?,
        expected: String,
    ) {
        val row = mapOf("type" to type, "nullable" to nullable, "dflt" to default, "extra" to extra)

        assertEquals(expected, mariaColumnDetail(row))
    }

    @ParameterizedTest
    @CsvSource("BASE TABLE", "SYSTEM VERSIONED", "SEQUENCE")
    fun `a table type that only shares a hash code with a known one is a view`(type: String) {
        val tables = listOf(mapOf("name" to "x", "kind" to collidingWith(type)))

        val tree = mariaSchemaTree("shop", MariaSchemaRows(tables = tables))

        assertEquals("views", tree.single().name)
    }

    @Test
    fun `events are sorted by name and shown with their status`() {
        val events =
            listOf(
                mapOf("name" to "c", "status" to "ENABLED"),
                mapOf("name" to "a", "status" to "DISABLED"),
                mapOf("name" to "b", "status" to "ENABLED"),
            )

        val tree = mariaSchemaTree("shop", MariaSchemaRows(events = events))

        assertEquals(listOf("a", "b", "c"), tree.single().children.map { it.name })
        assertEquals(listOf("disabled", "enabled", "enabled"), tree.single().children.map { it.detail })
    }

    @Test
    fun `a view with a trigger shows only its columns and a table its objects sorted by name`() {
        val tree =
            mariaSchemaTree(
                "shop",
                MariaSchemaRows(
                    tables =
                        listOf(
                            mapOf("name" to "v", "kind" to "VIEW"),
                            mapOf("name" to "t", "kind" to "BASE TABLE"),
                        ),
                    columns = listOf(mapOf("tbl" to "v", "name" to "c", "type" to "int")),
                    triggers =
                        listOf(
                            mapOf("tbl" to "v", "name" to "tv", "timing" to "AFTER", "event" to "INSERT"),
                            mapOf("tbl" to "t", "name" to "z", "timing" to "AFTER", "event" to "INSERT"),
                            mapOf("tbl" to "t", "name" to "a", "timing" to "AFTER", "event" to "INSERT"),
                        ),
                ),
            )

        val view = tree.single { it.name == "views" }.children.single()
        assertEquals(listOf("c"), view.children.map { it.name })
        val triggers =
            tree
                .single { it.name == "tables" }
                .children
                .single()
                .children
                .single()
                .children
        assertEquals(listOf("a", "z"), triggers.map { it.name })
    }

    @Test
    fun `routines of one name are ordered by kind`() {
        val routines =
            listOf(
                mapOf("name" to "f", "kind" to "PROCEDURE", "result" to null),
                mapOf("name" to "f", "kind" to "FUNCTION", "result" to null),
            )

        val tree = mariaSchemaTree("shop", MariaSchemaRows(routines = routines))

        assertEquals(listOf(ObjectKind.FUNCTION, ObjectKind.PROCEDURE), tree.single().children.map { it.kind })
    }
}
