package dev.sluide.databases.ui.tree

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.databases.Databases
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.connect.PasswordCache
import dev.sluide.databases.meta.DbObject
import dev.sluide.databases.meta.ObjectKind
import dev.sluide.databases.result.TableRef
import dev.sluide.databases.settings.DatabasesSettings
import dev.sluide.databases.sql.QueryHistory
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.FakeDialogs
import dev.sluide.databases.ui.ModelFixture
import dev.sluide.settings.SettingsStore
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.MenuItem
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The tree's wiring in a scene: lazy expansion, the error row, double click and Enter, F5, the context menu with its
 * actions and alerts, Refresh keeping expansion and selection, stale replies, Collapse All and the system-schemas
 * toggle. The decisions are in [ObjectTreeModelTest], the sync in [TreeSyncTest], the cache in [MetadataCacheTest].
 * All state is read on the FX thread. Needs a display, see the `uiTest` Gradle task.
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
        val robot: FxRobot,
    ) {
        val f = ModelFixture(dir)
        val introspector = FakeIntrospector()
        val session: MetadataSession
        val entry: ConnectionEntry
        val actions = Actions()
        val scratch = mutableListOf<Pair<String, String>>()
        val copied = mutableListOf<String>()
        val infos = mutableListOf<Pair<String, String>>()
        val errors = mutableListOf<Pair<String, String>>()
        lateinit var view: ObjectTreeView

        private fun table(name: String) =
            DbObject(
                ObjectKind.TABLE,
                "public",
                name,
                children = listOf(DbObject(ObjectKind.COLUMN, "public", "id", table = name, detail = "integer")),
            )

        fun tables(vararg names: String) =
            listOf(DbObject(ObjectKind.GROUP, "public", "tables", children = names.map(::table)))

        init {
            val project = dir.resolve("project").createDirectories()
            f.store.save(project, listOf(ConnectionConfig("a", "orders", DatabaseKind.POSTGRESQL)))
            f.model.load(project)
            entry = f.model.connections.single()
            session = MetadataSession(entry.config.get())
            introspector.contents["public"] = tables("orders")
            introspector.contents["sales"] = emptyList()
            fx {
                entry.connected(session)
                entry.workspaceActions = actions
                val cache = MetadataCache(entry, { it.run() }, { introspector }, { _, _ -> "public" })
                view =
                    ObjectTreeView(entry, f.model, cache, { ext, text -> scratch += ext to text }, { null }, {
                        copied +=
                            it
                    })
                view.showInfo = { title, message -> infos += title to message }
                view.showError = { title, message -> errors += title to message }
                stage.scene = Scene(view.node, 400.0, 500.0)
                stage.show()
                session.runAll()
            }
        }

        fun <T> fx(block: () -> T): T {
            val result = AtomicReference<T>()
            robot.interact { result.set(block()) }
            return result.get()
        }

        fun schemaItem(name: String): TreeItem<TreeRow> =
            view.tree.root.children
                .first { (it.value as? ObjectRow)?.obj?.name == name }

        fun ordersItem(): TreeItem<TreeRow> =
            schemaItem("public").children.single().children.first {
                label(it) ==
                    "orders"
            }

        fun label(item: TreeItem<TreeRow>) = ObjectTreeModel.label(item.value)

        fun topLabels() =
            view.tree.root.children
                .map(::label)

        fun expandPublic() =
            fx {
                schemaItem("public").isExpanded = true
                session.runAll()
            }

        fun cellOf(item: TreeItem<TreeRow>): TreeCell<TreeRow> {
            view.tree.applyCss()
            view.tree.layout()
            val cell =
                view.tree
                    .lookupAll(".tree-cell")
                    .map { it as TreeCell<*> }
                    .first { it.treeItem === item }

            @Suppress("UNCHECKED_CAST")
            return cell as TreeCell<TreeRow>
        }

        fun menuItems(item: TreeItem<TreeRow>): List<MenuItem> {
            // The items as JavaFX sees them before showing: it never shows an empty menu.
            val menu = checkNotNull(cellOf(item).contextMenu)
            return menu.items
        }

        fun click(
            target: Node,
            count: Int,
        ) = Event.fireEvent(
            target,
            MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                0.0,
                0.0,
                0.0,
                0.0,
                MouseButton.PRIMARY,
                count,
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

        fun press(code: KeyCode) =
            Event.fireEvent(view.tree, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))
    }

    @Test
    fun `expanding a schema loads its objects, a failure shows one error row and expanding again retries`(
        robot: FxRobot,
    ) {
        val t = Fixture(robot)
        assertEquals(
            listOf<TreeRow>(LoadingRow),
            t.fx {
                t.schemaItem("public").isExpanded = true
                t.schemaItem("public").children.map { it.value }
            },
        )
        t.fx { t.session.runAll() }
        assertEquals(
            listOf("Tables", "orders"),
            t.fx {
                listOf(t.label(t.schemaItem("public").children.single()), t.label(t.ordersItem()))
            },
        )

        t.introspector.failure = SQLException("ERROR: permission denied\n  Detail: x", "42501")
        t.fx {
            t.schemaItem("sales").isExpanded = true
            t.session.runAll()
        }
        assertEquals(
            listOf<TreeRow>(ErrorRow("permission denied")),
            t.fx { t.schemaItem("sales").children.map { it.value } },
        )

        t.introspector.failure = null
        val after =
            t.fx {
                t.schemaItem("sales").isExpanded = false
                t.schemaItem("sales").isExpanded = true
                t.session.runAll()
                t.schemaItem("sales").children.size
            }
        assertEquals(0, after)
    }

    @Test
    fun `a table shows its columns only after it is expanded`(robot: FxRobot) {
        val t = Fixture(robot)
        t.expandPublic()
        assertEquals(listOf<TreeRow>(LoadingRow), t.fx { t.ordersItem().children.map { it.value } })
        val columns =
            t.fx {
                t.ordersItem().isExpanded = true
                t.ordersItem().children.map { t.label(it) }
            }
        assertEquals(listOf("id"), columns)
    }

    @Test
    fun `double click and Enter on a table open its data, Enter on a schema expands it`(robot: FxRobot) {
        val t = Fixture(robot)
        t.expandPublic()
        val ref = TableRef(DatabaseKind.POSTGRESQL, "public", "orders")
        t.fx { t.click(t.cellOf(t.ordersItem()), 2) }
        assertEquals(listOf(ref), t.actions.opened)

        t.fx {
            t.view.tree.selectionModel
                .select(t.ordersItem())
            t.press(KeyCode.ENTER)
        }
        assertEquals(listOf(ref, ref), t.actions.opened)

        val expanded =
            t.fx {
                t.view.tree.selectionModel
                    .select(t.schemaItem("sales"))
                t.press(KeyCode.ENTER)
                t.schemaItem("sales").isExpanded
            }
        assertTrue(expanded)
    }

    @Test
    fun `a double click on the disclosure arrow does not open the data`(robot: FxRobot) {
        val t = Fixture(robot)
        t.expandPublic()
        t.fx {
            val cell = t.cellOf(t.ordersItem())
            val arrow = checkNotNull(cell.lookup(".tree-disclosure-node"))
            t.click(arrow, 2)
        }
        assertEquals(emptyList(), t.actions.opened)
    }

    @Test
    fun `the context menu of a table lists its actions and they copy, show DDL and insert`(robot: FxRobot) {
        val t = Fixture(robot)
        t.expandPublic()
        val items = t.fx { t.menuItems(t.ordersItem()) }
        assertEquals(
            listOf("Open Data", "Show DDL", "Copy Name", "Copy Qualified Name", "Generate SELECT", "", "Refresh"),
            items.map { it.text.orEmpty() },
        )

        fun fire(title: String) = t.fx { items.first { it.text == title }.fire() }
        fire("Copy Name")
        fire("Copy Qualified Name")
        assertEquals(listOf("orders", "\"public\".\"orders\""), t.copied)
        fire("Show DDL")
        t.fx { t.session.runAll() }
        assertEquals(listOf("sql" to "CREATE TABLE t ();\n"), t.scratch)
        fire("Generate SELECT")
        assertEquals(listOf("SELECT \"id\" FROM \"public\".\"orders\";"), t.actions.inserted)
    }

    @Test
    fun `a group's menu has Refresh only, without a leading separator`(robot: FxRobot) {
        val t = Fixture(robot)
        t.expandPublic()
        val items = t.fx { t.menuItems(t.schemaItem("public").children.single()) }
        assertEquals(listOf("Refresh"), items.map { it.text })
    }

    @Test
    fun `Show DDL tells the user when the object is gone or the read failed`(robot: FxRobot) {
        val t = Fixture(robot)
        t.expandPublic()
        val show = t.fx { t.menuItems(t.ordersItem()).first { it.text == "Show DDL" } }
        t.introspector.ddlText = ""
        t.fx {
            show.fire()
            t.session.runAll()
        }
        assertEquals(listOf("Show DDL" to "orders no longer exists. Refresh the tree."), t.infos)
        t.introspector.ddlFailure = SQLException("ERROR: connection lost")
        t.fx {
            show.fire()
            t.session.runAll()
        }
        assertEquals(listOf("Show DDL" to "connection lost"), t.errors)
        assertEquals(emptyList(), t.scratch)
    }

    @Test
    fun `Refresh and F5 reload, and the selection stays on its node when items appear before it`(robot: FxRobot) {
        val t = Fixture(robot)
        t.expandPublic()
        val orders =
            t.fx {
                t.view.tree.selectionModel
                    .select(t.ordersItem())
                t.ordersItem()
            }
        t.introspector.calls.clear()
        // A table appears before the selected one and a schema above its schema.
        t.introspector.contents["public"] = t.tables("accounts", "orders")
        t.introspector.schemaNames = listOf("alpha", "public", "sales")
        t.fx {
            t.view.refreshButton.fire()
            t.session.runAll()
        }
        assertEquals(listOf("schemas false", "schema public"), t.introspector.calls)
        assertEquals(listOf("alpha", "public", "sales"), t.fx { t.topLabels() })
        assertSame(orders, t.fx { t.ordersItem() })
        assertTrue(t.fx { t.schemaItem("public").isExpanded })
        assertSame(orders, t.fx { t.view.tree.selectionModel.selectedItem })

        t.introspector.calls.clear()
        t.fx {
            t.press(KeyCode.F5)
            t.session.runAll()
        }
        assertEquals(listOf("schemas false", "schema public"), t.introspector.calls)
        assertSame(orders, t.fx { t.view.tree.selectionModel.selectedItem })
    }

    @Test
    fun `Refresh keeps an expanded table open and shows its new columns at once`(robot: FxRobot) {
        val t = Fixture(robot)
        t.expandPublic()
        val orders =
            t.fx {
                t.ordersItem().isExpanded = true
                t.ordersItem()
            }
        assertEquals(listOf("id"), t.fx { orders.children.map { t.label(it) } })
        t.introspector.contents["public"] =
            listOf(
                DbObject(
                    ObjectKind.GROUP,
                    "public",
                    "tables",
                    children =
                        listOf(
                            DbObject(
                                ObjectKind.TABLE,
                                "public",
                                "orders",
                                children =
                                    listOf("id", "total").map {
                                        DbObject(ObjectKind.COLUMN, "public", it, table = "orders")
                                    },
                            ),
                        ),
                ),
            )
        t.fx {
            t.view.refreshButton.fire()
            t.session.runAll()
        }
        assertSame(orders, t.fx { t.ordersItem() })
        assertTrue(t.fx { orders.isExpanded })
        assertEquals(listOf("id", "total"), t.fx { orders.children.map { t.label(it) } })
        // The row's object is the new one too, so Generate SELECT lists the new columns.
        val selected = t.fx { (orders.value as ObjectRow).obj.children.map { it.name } }
        assertEquals(listOf("id", "total"), selected)
    }

    @Test
    fun `a failed schema list reload keeps the schemas and their expansion and adds an error row`(robot: FxRobot) {
        val t = Fixture(robot)
        t.expandPublic()
        val public = t.fx { t.schemaItem("public") }
        t.introspector.failure = SQLException("ERROR: connection lost")
        t.fx {
            t.view.refreshButton.fire()
            t.session.runAll()
        }
        assertEquals(listOf("public", "sales", "connection lost"), t.fx { t.topLabels() })
        assertSame(public, t.fx { t.schemaItem("public") })
        assertTrue(t.fx { public.isExpanded })
    }

    @Test
    fun `a stale reply that arrives after Refresh is dropped`(robot: FxRobot) {
        val t = Fixture(robot)
        // The load of public is under way when Refresh starts a new one.
        t.fx { t.schemaItem("public").isExpanded = true }
        t.fx { t.view.refreshButton.fire() }
        t.introspector.contents["public"] = t.tables("old")
        t.fx { t.session.queue.removeAt(0)() }
        // The reply of the first load is not shown.
        assertEquals(listOf<TreeRow>(LoadingRow), t.fx { t.schemaItem("public").children.map { it.value } })
        t.introspector.contents["public"] = t.tables("new")
        t.fx { t.session.runAll() }
        assertEquals(
            listOf("new"),
            t.fx {
                t
                    .schemaItem("public")
                    .children
                    .single()
                    .children
                    .map { t.label(it) }
            },
        )
    }

    @Test
    fun `Collapse All collapses every expanded item`(robot: FxRobot) {
        val t = Fixture(robot)
        t.expandPublic()
        assertTrue(
            t.fx {
                t
                    .schemaItem("public")
                    .children
                    .single()
                    .isExpanded
            },
        )
        val open =
            t.fx {
                t.view.collapseButton.fire()
                listOf(t.schemaItem("public"), t.schemaItem("public").children.single()).any { it.isExpanded }
            }
        assertFalse(open)
    }

    @Test
    fun `the tree still follows a new session and Refresh after it left the scene and came back`(robot: FxRobot) {
        val t = Fixture(robot)
        val scene = stage.scene
        // As when another editor tab is selected and this one again.
        t.fx { scene.root = StackPane() }
        t.fx { scene.root = t.view.node }
        t.introspector.schemaNames = listOf("fresh")
        val next = MetadataSession(t.entry.config.get())
        t.fx {
            t.entry.connected(next)
            next.runAll()
        }
        assertEquals(listOf("fresh"), t.fx { t.topLabels() })

        t.introspector.schemaNames = listOf("again")
        t.fx {
            t.view.refreshButton.fire()
            next.runAll()
        }
        assertEquals(listOf("again"), t.fx { t.topLabels() })
    }

    @Test
    fun `the system schemas toggle changes the connection and reloads, as does the edit dialog`(robot: FxRobot) {
        val t = Fixture(robot)
        t.introspector.calls.clear()
        t.fx {
            t.view.systemToggle.fire()
            t.session.runAll()
        }
        assertTrue(
            t.fx {
                t.entry.config
                    .get()
                    .showSystemSchemas
            },
        )
        assertEquals(listOf("schemas true"), t.introspector.calls)
        assertTrue(t.fx { "pg_catalog" in t.topLabels() })

        // The Edit dialog changes the config without the toggle.
        t.introspector.calls.clear()
        t.fx {
            t.entry.setConfig(
                t.entry.config
                    .get()
                    .copy(showSystemSchemas = false),
            )
            t.session.runAll()
        }
        assertFalse(t.fx { t.view.systemToggle.isSelected })
        assertEquals(listOf("schemas false"), t.introspector.calls)
        assertFalse(t.fx { "pg_catalog" in t.topLabels() })
    }

    @Test
    fun `the bean builds the tree with its current openScratch`(robot: FxRobot) {
        val databases =
            Databases(
                ModelFixture(dir).store,
                PasswordCache(),
                QueryHistory(),
                DatabasesSettings(SettingsStore(dir.resolve("settings.properties"))),
                BackgroundTasks.forTests(),
                { FakeDialogs() },
                { _, _ -> error("not connected") },
                { it.run() },
                { c, _, _, _, _, _ -> MetadataSession(c) },
            )
        val entry = ConnectionEntry(ConnectionConfig("o", "orders", DatabaseKind.POSTGRESQL), "|o")
        val received = mutableListOf<Pair<String, String>>()
        val view = AtomicReference<ObjectTreeView>()
        robot.interact { view.set(databases.objectTree(entry)) }
        // Set after the tree was built: the tree reads it when it needs it.
        databases.openScratch = { ext, text -> received += ext to text }
        view.get().openScratch("sql", "SELECT 1;")
        assertEquals(listOf("sql" to "SELECT 1;"), received)
    }
}
