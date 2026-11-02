package dev.sluide.databases.ui.tree

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.meta.DbObject
import dev.sluide.databases.meta.ObjectKind
import dev.sluide.databases.result.TableRef
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.ModelFixture
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.MenuItem
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import javafx.stage.WindowEvent
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import java.sql.SQLException
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The tree's wiring in a scene: lazy expansion, the error row, double click and Enter, the context menu's Show DDL,
 * Refresh keeping the expansion, and the system-schemas toggle. The decisions are in [ObjectTreeModelTest] and the
 * cache in [MetadataCacheTest]. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ObjectTreeViewUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
    }

    private class Actions : WorkspaceActions {
        val opened = mutableListOf<TableRef>()
        val inserted = mutableListOf<String>()

        override fun openData(ref: TableRef) {
            opened += ref
        }

        override fun insertIntoConsole(text: String) {
            inserted += text
        }
    }

    private inner class Fixture(
        robot: FxRobot,
    ) {
        val f = ModelFixture(dir)
        val introspector = FakeIntrospector()
        val session: MetadataSession
        val entry: ConnectionEntry
        val actions = Actions()
        val scratch = mutableListOf<Pair<String, String>>()
        lateinit var view: ObjectTreeView

        init {
            val project = dir.resolve("project").createDirectories()
            f.store.save(project, listOf(ConnectionConfig("a", "orders", DatabaseKind.POSTGRESQL)))
            f.model.load(project)
            entry = f.model.connections.single()
            session = MetadataSession(entry.config.get())
            val orders =
                DbObject(
                    ObjectKind.TABLE,
                    "public",
                    "orders",
                    children =
                        listOf(
                            DbObject(ObjectKind.COLUMN, "public", "id", table = "orders", detail = "integer"),
                        ),
                )
            introspector.contents["public"] =
                listOf(DbObject(ObjectKind.GROUP, "public", "tables", children = listOf(orders)))
            introspector.contents["sales"] = emptyList()
            robot.interact {
                entry.connected(session)
                entry.workspaceActions = actions
                val cache = MetadataCache(entry, { it.run() }, { introspector }, { _, _ -> "public" })
                view = ObjectTreeView(entry, f.model, cache) { ext, text -> scratch += ext to text }
                stage.scene = Scene(view.node, 400.0, 500.0)
                stage.show()
                session.runAll()
            }
        }

        fun schemaItem(name: String): TreeItem<TreeRow> =
            view.tree.root.children.first {
                (it.value as ObjectRow).obj.name ==
                    name
            }

        fun ordersItem(): TreeItem<TreeRow> =
            schemaItem("public")
                .children
                .single()
                .children
                .single()

        fun cellOf(item: TreeItem<TreeRow>): TreeCell<TreeRow> {
            view.tree.applyCss()
            view.tree.layout()
            return view.tree
                .lookupAll(".tree-cell")
                .map { it as TreeCell<*> }
                .first { it.treeItem === item }
                .let {
                    @Suppress("UNCHECKED_CAST")
                    it as TreeCell<TreeRow>
                }
        }

        fun menuItems(item: TreeItem<TreeRow>): List<MenuItem> {
            val menu = checkNotNull(cellOf(item).contextMenu)
            menu.onShowing.handle(WindowEvent(menu, WindowEvent.WINDOW_SHOWING))
            return menu.items
        }
    }

    private fun key(code: KeyCode) = KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)

    @Test
    fun `expanding a schema loads its objects, a failure shows one error row and expanding again retries`(
        robot: FxRobot,
    ) {
        val t = Fixture(robot)
        robot.interact { t.schemaItem("public").isExpanded = true }
        assertEquals(
            listOf(LoadingRow),
            t
                .schemaItem("public")
                .children
                .map { it.value },
        )
        robot.interact { t.session.runAll() }
        assertEquals(
            "Tables",
            ObjectTreeModel.label(
                t
                    .schemaItem("public")
                    .children
                    .single()
                    .value,
            ),
        )
        assertEquals("orders", ObjectTreeModel.label(t.ordersItem().value))

        t.introspector.failure = SQLException("ERROR: permission denied\n  Detail: x", "42501")
        robot.interact {
            t.schemaItem("sales").isExpanded = true
            t.session.runAll()
        }
        assertEquals(listOf<TreeRow>(ErrorRow("permission denied")), t.schemaItem("sales").children.map { it.value })

        t.introspector.failure = null
        robot.interact {
            t.schemaItem("sales").isExpanded = false
            t.schemaItem("sales").isExpanded = true
            t.session.runAll()
        }
        assertTrue(t.schemaItem("sales").children.isEmpty())
    }

    @Test
    fun `double click and Enter on a table open its data, Enter on a schema expands it`(robot: FxRobot) {
        val t = Fixture(robot)
        robot.interact {
            t.schemaItem("public").isExpanded = true
            t.session.runAll()
        }
        val ref = TableRef(DatabaseKind.POSTGRESQL, "public", "orders")
        robot.interact {
            val cell = t.cellOf(t.ordersItem())
            Event.fireEvent(
                cell,
                MouseEvent(
                    MouseEvent.MOUSE_CLICKED,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    MouseButton.PRIMARY,
                    2,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    true,
                    false,
                    false,
                    null,
                ),
            )
        }
        assertEquals(listOf(ref), t.actions.opened)

        robot.interact {
            t.view.tree.selectionModel
                .select(t.ordersItem())
            Event.fireEvent(t.view.tree, key(KeyCode.ENTER))
        }
        assertEquals(listOf(ref, ref), t.actions.opened)

        robot.interact {
            t.view.tree.selectionModel
                .select(t.schemaItem("sales"))
            Event.fireEvent(t.view.tree, key(KeyCode.ENTER))
        }
        assertTrue(t.schemaItem("sales").isExpanded)
    }

    @Test
    fun `the context menu of a table lists its actions, Show DDL opens a scratch and Generate SELECT inserts`(
        robot: FxRobot,
    ) {
        val t = Fixture(robot)
        robot.interact {
            t.schemaItem("public").isExpanded = true
            t.session.runAll()
        }
        lateinit var items: List<MenuItem>
        robot.interact { items = t.menuItems(t.ordersItem()) }
        assertEquals(
            listOf("Open Data", "Show DDL", "Copy Name", "Copy Qualified Name", "Generate SELECT", "", "Refresh"),
            items.map { it.text.orEmpty() },
        )
        robot.interact {
            items.first { it.text == "Show DDL" }.fire()
            t.session.runAll()
        }
        assertEquals(listOf("sql" to "CREATE TABLE t ();\n"), t.scratch)
        robot.interact { items.first { it.text == "Generate SELECT" }.fire() }
        assertEquals(listOf("SELECT \"id\" FROM \"public\".\"orders\";"), t.actions.inserted)
    }

    @Test
    fun `Refresh reloads the expanded schemas and keeps expansion and selection`(robot: FxRobot) {
        val t = Fixture(robot)
        robot.interact {
            t.schemaItem("public").isExpanded = true
            t.session.runAll()
            t.view.tree.selectionModel
                .select(t.ordersItem())
        }
        val orders = t.ordersItem()
        t.introspector.calls.clear()
        robot.interact {
            t.view.refreshButton.fire()
            t.session.runAll()
        }
        assertEquals(listOf("schemas false", "schema public"), t.introspector.calls)
        assertSame(orders, t.ordersItem())
        assertTrue(t.schemaItem("public").isExpanded)
        assertSame(orders, t.view.tree.selectionModel.selectedItem)
    }

    @Test
    fun `the tree still follows a new session and Refresh after it left the scene and came back`(robot: FxRobot) {
        val t = Fixture(robot)
        val scene = stage.scene
        // As when another editor tab is selected and this one again.
        robot.interact { scene.root = javafx.scene.layout.StackPane() }
        robot.interact { scene.root = t.view.node }
        t.introspector.schemaNames = listOf("fresh")
        val next = MetadataSession(t.entry.config.get())
        robot.interact {
            t.entry.connected(next)
            next.runAll()
        }
        assertEquals(
            listOf("fresh"),
            t.view.tree.root.children
                .map { (it.value as ObjectRow).obj.name },
        )

        t.introspector.schemaNames = listOf("again")
        robot.interact {
            t.view.refreshButton.fire()
            next.runAll()
        }
        assertEquals(
            listOf("again"),
            t.view.tree.root.children
                .map { (it.value as ObjectRow).obj.name },
        )
    }

    @Test
    fun `the system schemas toggle changes the connection and reloads the schemas`(robot: FxRobot) {
        val t = Fixture(robot)
        t.introspector.calls.clear()
        robot.interact {
            t.view.systemToggle.fire()
            t.session.runAll()
        }
        assertTrue(
            t.entry.config
                .get()
                .showSystemSchemas,
        )
        assertEquals(listOf("schemas true"), t.introspector.calls)
        assertTrue(
            t.view.tree.root.children
                .any { (it.value as ObjectRow).obj.name == "pg_catalog" },
        )
    }
}
