package dev.sluide.databases.ui.tree

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.meta.DbObject
import dev.sluide.databases.meta.ObjectKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.kordamp.ikonli.IkonHandler
import java.sql.SQLException
import java.util.ServiceLoader
import java.util.concurrent.CompletionException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObjectTreeModelTest {
    private fun column(name: String) = DbObject(ObjectKind.COLUMN, "public", name, table = "orders")

    private fun table(vararg columns: String) =
        DbObject(ObjectKind.TABLE, "public", "orders", children = columns.map(::column))

    @Test
    fun `schemas show a Loading row until loaded, then groups and objects with their keys`() {
        val loading = nodes(listOf("public")) { Load.Loading }
        assertEquals(listOf(LoadingRow), loading.single().children.map { it.value })

        val groups = listOf(DbObject(ObjectKind.GROUP, "public", "tables", children = listOf(table("id"))))
        val loaded = nodes(listOf("public"), expanded = { true }) { Load.Loaded(groups) }
        val tables = loaded.single().children.single()
        assertTrue(tables.expanded)
        val orders = tables.children.single()
        assertEquals(ObjectTreeModel.schemaKey("public"), tables.key.parent)
        assertEquals(tables.key, orders.key.parent)
        assertEquals(ObjectKind.TABLE, orders.key.kind)
        assertEquals("id", (orders.children.single().value as ObjectRow).obj.name)
        assertFalse(orders.expanded)
    }

    @Test
    fun `a table gets one placeholder child until its key is expanded`() {
        val groups = listOf(DbObject(ObjectKind.GROUP, "public", "tables", children = listOf(table("id"), table())))
        val collapsed =
            nodes(listOf("public")) { Load.Loaded(groups) }
                .single()
                .children
                .single()
                .children
        assertEquals(listOf<TreeRow>(LoadingRow), collapsed[0].children.map { it.value })
        // No columns: no arrow.
        assertEquals(emptyList(), collapsed[1].children)
        val open = nodes(listOf("public"), expanded = { it.kind == ObjectKind.TABLE }) { Load.Loaded(groups) }
        assertEquals(
            "id",
            (
                open
                    .single()
                    .children
                    .single()
                    .children[0]
                    .children
                    .single()
                    .value as ObjectRow
            ).obj.name,
        )
    }

    @Test
    fun `a failed schema list keeps the last schemas and adds the error as a last row`() {
        val kept = nodes(listOf("a", "b"), error = "down") { Load.Loading }
        assertEquals(listOf("a", "b"), kept.take(2).map { (it.value as ObjectRow).obj.name })
        assertEquals(ErrorRow("down"), kept.last().value)
        assertEquals(3, kept.size)
        assertEquals(listOf<TreeRow>(LoadingRow), nodes(null) { Load.Loading }.map { it.value })
    }

    @Test
    fun `rows are the same when their objects are equal, objects with children by identity`() {
        val a = ObjectRow(table("id"))
        val equalCopy = ObjectRow(table("id"))
        assertFalse(ObjectTreeModel.sameRow(a, equalCopy))
        assertTrue(ObjectTreeModel.sameRow(a, a))
        assertTrue(ObjectTreeModel.sameRow(ObjectRow(column("id")), ObjectRow(column("id"))))
        assertFalse(ObjectTreeModel.sameRow(ObjectRow(column("id")), ObjectRow(column("x"))))
    }

    private fun nodes(
        names: List<String>?,
        error: String? = null,
        expanded: (NodeKey) -> Boolean = { false },
        load: (String) -> Load<List<DbObject>>,
    ) = ObjectTreeModel.nodes(names, error, load, expanded)

    @Test
    fun `failures show one error row and overloads get different keys`() {
        val failed = nodes(listOf("public")) { Load.Failed("nope") }
        assertEquals(
            ErrorRow("nope"),
            failed
                .single()
                .children
                .single()
                .value,
        )
        assertEquals(ErrorRow("down"), nodes(null, error = "down") { Load.Loading }.single().value)

        val overloads =
            listOf(
                DbObject(ObjectKind.FUNCTION, "public", "f", ref = "integer"),
                DbObject(ObjectKind.FUNCTION, "public", "f", ref = "text"),
            )
        val nodes = nodes(listOf("public")) { Load.Loaded(overloads) }
        assertEquals(
            2,
            nodes
                .single()
                .children
                .map { it.key }
                .toSet()
                .size,
        )
    }

    @Test
    fun `labels capitalise groups, and details are the muted text`() {
        val group = ObjectRow(DbObject(ObjectKind.GROUP, "public", "foreign keys"))
        assertEquals("Foreign keys", ObjectTreeModel.label(group))
        assertEquals("Loading…", ObjectTreeModel.label(LoadingRow))
        assertEquals("orders", ObjectTreeModel.label(ObjectRow(table())))
        assertEquals(
            "integer not null",
            ObjectTreeModel.detail(ObjectRow(column("id").copy(detail = "integer not null"))),
        )
        assertEquals("", ObjectTreeModel.detail(LoadingRow))
    }

    @Test
    fun `context actions per kind`() {
        assertEquals(
            listOf(
                NodeAction.OPEN_DATA,
                NodeAction.SHOW_DDL,
                NodeAction.COPY_NAME,
                NodeAction.COPY_QUALIFIED_NAME,
                NodeAction.GENERATE_SELECT,
            ),
            ObjectTreeModel.actions(ObjectKind.TABLE),
        )
        assertEquals(
            listOf(NodeAction.COPY_NAME, NodeAction.COPY_QUALIFIED_NAME),
            ObjectTreeModel.actions(ObjectKind.SCHEMA),
        )
        assertEquals(
            listOf(NodeAction.SHOW_DDL, NodeAction.COPY_NAME, NodeAction.COPY_QUALIFIED_NAME),
            ObjectTreeModel.actions(ObjectKind.COLUMN),
        )
        assertEquals(emptyList(), ObjectTreeModel.actions(ObjectKind.GROUP))
        assertTrue(ObjectTreeModel.opensData(table()))
        assertFalse(ObjectTreeModel.opensData(column("id")))
    }

    @Test
    fun `qualified names`() {
        val pg = DatabaseKind.POSTGRESQL
        assertEquals("\"public\".\"orders\"", ObjectTreeModel.qualifiedName(pg, table()))
        assertEquals("\"public\".\"orders\".\"id\"", ObjectTreeModel.qualifiedName(pg, column("id")))
        assertEquals(
            "`shop`",
            ObjectTreeModel.qualifiedName(DatabaseKind.MARIADB, DbObject(ObjectKind.SCHEMA, "shop", "shop")),
        )
        assertEquals(
            "\"pk\"",
            ObjectTreeModel.qualifiedName(pg, DbObject(ObjectKind.PRIMARY_KEY, "public", "pk", table = "orders")),
        )
        assertEquals(
            "\"public\".\"f\"",
            ObjectTreeModel.qualifiedName(pg, DbObject(ObjectKind.FUNCTION, "public", "f")),
        )
        // A PostgreSQL index lives in the schema; MariaDB's belongs to its table.
        val index = DbObject(ObjectKind.INDEX, "public", "orders_idx", table = "orders")
        assertEquals("\"public\".\"orders_idx\"", ObjectTreeModel.qualifiedName(pg, index))
        assertEquals("`orders_idx`", ObjectTreeModel.qualifiedName(DatabaseKind.MARIADB, index))
        // Overloads: the identity arguments.
        assertEquals(
            "\"public\".\"f\"(integer, text)",
            ObjectTreeModel.qualifiedName(pg, DbObject(ObjectKind.FUNCTION, "public", "f", ref = "integer, text")),
        )
    }

    @Test
    fun `generate select puts up to three columns on one line, more one per line`() {
        val pg = DatabaseKind.POSTGRESQL
        assertEquals(
            "SELECT \"a\", \"b\", \"c\" FROM \"public\".\"orders\";",
            ObjectTreeModel.generateSelect(pg, table("a", "b", "c")),
        )
        assertEquals(
            "SELECT\n    \"a\",\n    \"b\",\n    \"c\",\n    \"d\"\nFROM \"public\".\"orders\";",
            ObjectTreeModel.generateSelect(pg, table("a", "b", "c", "d")),
        )
        assertEquals("SELECT * FROM `public`.`orders`;", ObjectTreeModel.generateSelect(DatabaseKind.MARIADB, table()))
    }

    @Test
    fun `error messages are one line without SQLState noise`() {
        val failure = CompletionException(SQLException("ERROR: permission denied for schema x\n  Position: 3", "42501"))
        assertEquals("permission denied for schema x", ObjectTreeModel.errorMessage(failure))
        assertEquals("IllegalStateException", ObjectTreeModel.errorMessage(IllegalStateException()))
    }

    @ParameterizedTest
    @EnumSource(ObjectKind::class)
    fun `every kind has an icon that exists in the pack`(kind: ObjectKind) {
        val literal = ObjectIcons.literal(kind)
        val handlers = ServiceLoader.load(IkonHandler::class.java).toList()
        assertTrue(handlers.any { it.supports(literal) && it.resolve(literal) != null }, literal)
    }
}
