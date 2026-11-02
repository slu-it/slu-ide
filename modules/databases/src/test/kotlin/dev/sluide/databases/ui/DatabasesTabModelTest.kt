package dev.sluide.databases.ui

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.ui.dialog.ConnectionEdit
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The list side of [DatabasesTabModel]: loading, adding, editing, copying, deleting and moving, always saved. */
class DatabasesTabModelTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var f: ModelFixture
    private lateinit var project: Path

    private val orders = ConnectionConfig("o", "orders-dev", DatabaseKind.POSTGRESQL, user = "app")
    private val shop = ConnectionConfig("s", "shop", DatabaseKind.MARIADB)

    @BeforeEach
    fun setUp() {
        f = ModelFixture(dir)
        project = dir.resolve("project").createDirectories()
        f.store.save(project, listOf(orders, shop))
        f.model.load(project)
    }

    private fun names() = f.model.connections.map { it.config.get().name }

    private fun stored() = f.store.load(project)

    @Test
    fun `loads the project's connections with their history keys, and the shared ones without a folder`() {
        assertEquals(listOf(orders, shop), f.model.connections.map { it.config.get() })
        assertEquals("$project|o", f.model.connections[0].historyKey)
        assertEquals(project, f.model.folder)

        f.store.save(null, listOf(shop))
        f.model.load(null)

        assertEquals(listOf("shop"), names())
        assertEquals(
            "|s",
            f.model.connections
                .single()
                .historyKey,
        )
    }

    @Test
    fun `add opens the dialog for a new connection, appends, selects and saves it, and caches its password`() {
        f.dialogs.edit = { ConnectionEdit(it.copy(name = "reports", host = "db"), "pw".toCharArray()) }

        f.model.add(DatabaseKind.POSTGRESQL)

        assertEquals(listOf("edit PostgreSQL new=true names=[orders-dev, shop]"), f.dialogs.asked)
        assertEquals(listOf("orders-dev", "shop", "reports"), names())
        assertSame(f.model.connections[2], f.model.selected.get())
        assertEquals(ConnectionConfig("id1", "reports", DatabaseKind.POSTGRESQL, host = "db"), stored()[2])
        assertEquals("pw", String(checkNotNull(f.passwords.get(project, "id1"))))
    }

    @Test
    fun `a cancelled add changes nothing`() {
        f.model.add(DatabaseKind.MARIADB)

        assertEquals(listOf("orders-dev", "shop"), names())
        assertEquals(listOf(orders, shop), stored())
    }

    @Test
    fun `edit replaces the config and keeps the id, a connected entry keeps its session`() {
        val entry = f.model.connections[0]
        val session = f.connected(entry)
        f.dialogs.edit = { ConnectionEdit(it.copy(id = "other", name = "orders"), null) }

        f.model.edit(entry)

        assertEquals("edit orders-dev new=false names=[orders-dev, shop]", f.dialogs.asked.single())
        assertEquals(orders.copy(name = "orders"), entry.config.get())
        assertEquals(orders.copy(name = "orders"), stored()[0])
        assertSame(session, entry.session.get())
        assertEquals(ConnectionStatus.CONNECTED, entry.status.get())
    }

    @Test
    fun `duplicate inserts a copy with a new id after the original, numbering further copies`() {
        val entry = f.model.connections[0]

        f.model.duplicate(entry)
        f.model.duplicate(entry)

        assertEquals(listOf("orders-dev", "orders-dev (copy 2)", "orders-dev (copy)", "shop"), names())
        assertEquals(listOf("o", "id2", "id1", "s"), stored().map { it.id })
        assertEquals(orders.copy(id = "id2", name = "orders-dev (copy 2)"), stored()[1])
        assertSame(f.model.connections[1], f.model.selected.get())
    }

    @Test
    fun `copy names skip taken ones, ignoring case`() {
        assertEquals("a (copy)", DatabasesTabModel.copyName("a", setOf("a")))
        assertEquals("a (copy 3)", DatabasesTabModel.copyName("a", setOf("a", "A (Copy)", "a (copy 2)")))
    }

    @Test
    fun `delete asks, removes, saves and forgets the password, Cancel keeps it`() {
        val entry = f.model.connections[0]
        f.passwords.put(project, "o", "pw".toCharArray())
        f.model.selected.set(entry)
        f.dialogs.delete = false

        f.model.delete(entry)
        assertEquals(listOf("orders-dev", "shop"), names())

        f.dialogs.delete = true
        f.model.delete(entry)

        assertEquals(listOf("delete orders-dev", "delete orders-dev"), f.dialogs.asked)
        assertEquals(listOf(shop), stored())
        assertNull(f.passwords.get(project, "o"))
        assertSame(f.model.connections[0], f.model.selected.get(), "the next card is selected")
    }

    @Test
    fun `deleting a connected entry disconnects it first, and its questions' Cancel keeps it`() {
        val entry = f.model.connections[0]
        val session = f.connected(entry)
        session.pendingTransaction = true

        f.model.delete(entry)
        assertEquals(listOf("delete orders-dev", "transaction orders-dev"), f.dialogs.asked)
        assertEquals(listOf("orders-dev", "shop"), names())
        assertEquals(listOf(), session.calls)

        f.dialogs.transactionEnd = TransactionEnd.ROLLBACK
        f.model.delete(entry)

        assertEquals(listOf("rollback", "close"), session.calls)
        assertEquals(listOf(shop), stored())
    }

    @Test
    fun `move reorders and saves`() {
        f.model.move(1, 0)

        assertEquals(listOf("shop", "orders-dev"), names())
        assertEquals(listOf(shop, orders), stored())
    }

    @Test
    fun `show system schemas is saved per connection`() {
        f.model.setShowSystemSchemas(f.model.connections[1], true)

        assertEquals(listOf(orders, shop.copy(showSystemSchemas = true)), stored())
    }

    @Test
    fun `a failed save says so and keeps the change`() {
        dir.resolve("shared.json").writeText("not json")
        f.model.load(null)

        f.dialogs.edit = { ConnectionEdit(it.copy(name = "local"), null) }
        f.model.add(DatabaseKind.MARIADB)

        assertEquals(listOf("local"), names())
        assertEquals(
            "Connections not saved",
            f.dialogs.errors
                .single()
                .first,
        )
    }
}
