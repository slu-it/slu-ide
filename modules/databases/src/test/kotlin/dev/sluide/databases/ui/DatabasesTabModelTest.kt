package dev.sluide.databases.ui

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.ui.dialog.ConnectionEdit
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The list side of [DatabasesTabModel]: loading, adding, editing, copying, deleting and moving, always saved; the saved
 * passwords of the dialogs, copies and Test Connection.
 */
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
        f.dialogs.edit =
            { ConnectionEdit(it.copy(name = "reports", host = "db"), "pw".toCharArray(), savePassword = false) }

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
        f.dialogs.edit = { ConnectionEdit(it.copy(id = "other", name = "orders"), null, savePassword = false) }

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

        f.dialogs.edit = { ConnectionEdit(it.copy(name = "local"), null, savePassword = false) }
        f.model.add(DatabaseKind.MARIADB)

        assertEquals(listOf("local"), names())
        assertEquals(
            "Connections not saved",
            f.dialogs.errors
                .single()
                .first,
        )
    }

    /** Stores orders with the saved password [password] and returns its entry. */
    private fun savedOrders(password: String): ConnectionEntry {
        f.store.save(project, listOf(orders.copy(savedPassword = FakeSavedPasswords.value("o", password)), shop))
        f.model.load(project)
        return f.model.connections[0]
    }

    @Test
    fun `add with Save password encrypts the typed one for the new id once the dialog closed`() {
        f.dialogs.edit = { ConnectionEdit(it.copy(name = "reports"), "pw".toCharArray(), savePassword = true) }

        f.model.add(DatabaseKind.POSTGRESQL)
        assertNull(stored()[2].savedPassword)
        f.runWorkers()

        assertEquals(listOf("encrypt id1 pw"), f.saved.calls)
        assertEquals(FakeSavedPasswords.value("id1", "pw"), stored()[2].savedPassword)
        assertEquals("pw", String(checkNotNull(f.passwords.get(project, "id1"))))
        assertEquals(2, f.zeroed.size, "the dialog's password and the copy that was encrypted")
        assertTrue(f.zeroed.all { array -> array.all { it == '\u0000' } })
    }

    @Test
    fun `edit keeps the saved password, replaces it with a typed one once encrypted, and unchecked removes it`() {
        val entry = savedOrders("old")
        val old = FakeSavedPasswords.value("o", "old")
        // The dialog's config never carries the saved value.
        f.dialogs.edit = { ConnectionEdit(it.copy(name = "orders", savedPassword = null), null, savePassword = true) }
        f.model.edit(entry)
        assertEquals(orders.copy(name = "orders", savedPassword = old), stored()[0])

        entry.savedRejected = true
        f.dialogs.edit = { ConnectionEdit(it.copy(savedPassword = null), "new".toCharArray(), savePassword = true) }
        f.model.edit(entry)
        assertEquals(old, stored()[0].savedPassword, "until the worker encrypted the new one")
        assertEquals("new", String(checkNotNull(f.passwords.get(project, "o"))))
        f.runWorkers()
        assertEquals(FakeSavedPasswords.value("o", "new"), stored()[0].savedPassword)
        assertFalse(entry.savedRejected)

        entry.savedRejected = true
        f.dialogs.edit = { ConnectionEdit(it, null, savePassword = false) }
        f.model.edit(entry)
        assertNull(stored()[0].savedPassword)
        assertFalse(entry.savedRejected)
        assertFalse(f.workersPending())
    }

    @Test
    fun `duplicate encrypts the saved password again for the copy's id, a failure leaves the copy without one`() {
        val entry = savedOrders("pw")

        f.model.duplicate(entry)
        assertNull(stored()[1].savedPassword)
        f.runWorkers()

        assertEquals(listOf("decrypt o v1:o:pw", "encrypt id1 pw"), f.saved.calls)
        assertEquals(FakeSavedPasswords.value("id1", "pw"), stored()[1].savedPassword)
        assertTrue(f.saved.allZeroed())

        f.saved.encryptFails = "Keychain access denied"
        f.model.duplicate(entry)
        f.runWorkers()

        assertEquals("orders-dev (copy 2)", stored()[1].name)
        assertNull(stored()[1].savedPassword)
        assertEquals(listOf(), f.dialogs.errors)

        f.saved.duringDecrypt = { throw IllegalStateException("the store failed") }
        f.model.duplicate(entry)
        f.runWorkers()
        assertEquals("orders-dev (copy 3)", stored()[1].name)
        assertNull(stored()[1].savedPassword)
    }

    @Test
    fun `a copy that got its own saved password keeps it when the copied one arrives`() {
        f.model.duplicate(savedOrders("pw"))
        val copy = f.model.connections[1]

        f.model.setSavedPassword(copy, "v1:own")
        f.runWorkers()

        assertEquals("v1:own", stored()[1].savedPassword)
    }

    @Test
    fun `forgetting the saved password removes it and the one in memory without a question`() {
        val entry = savedOrders("pw")
        f.passwords.put(project, "o", "pw".toCharArray())
        entry.savedRejected = true

        f.model.forgetSavedPassword(entry)

        assertNull(stored()[0].savedPassword)
        assertNull(f.passwords.get(project, "o"))
        assertFalse(entry.savedRejected)
        assertEquals(listOf(), f.dialogs.asked)
    }

    @Test
    fun `Test Connection with an empty field uses the in-memory password, else the saved one, else none`() {
        val entry = savedOrders("stored")
        f.model.edit(entry)
        val tester = checkNotNull(f.dialogs.tester)
        val config = entry.config.get()

        assertEquals("Test 1", tester.test(config, "typed".toCharArray()).get())
        f.passwords.put(project, "o", "cached".toCharArray())
        assertEquals("Test 1", tester.test(config, null).get())
        assertTrue(f.testArrays.last().all { it == '\u0000' }, "the in-memory copy is zeroed")

        f.passwords.forget(project, "o")
        val saved = tester.test(config, null)
        assertFalse(saved.isDone, "decrypted on a worker")
        f.runWorkers()
        assertEquals("Test 1", saved.get())
        assertTrue(f.saved.allZeroed())

        f.model.add(DatabaseKind.POSTGRESQL)
        assertEquals("Test 1", checkNotNull(f.dialogs.tester).test(config, null).get())

        assertEquals(listOf("typed", "cached", "stored", null), f.testedWith)
    }

    @ParameterizedTest
    @CsvSource(
        "v1:elsewhere:x,,it was saved with another key",
        "v1:o:stored,Keychain access denied,Keychain access denied",
    )
    fun `Test Connection with an empty field fails when the saved password cannot be read`(
        value: String,
        noKey: String?,
        reason: String,
    ) {
        f.store.save(project, listOf(orders.copy(savedPassword = value)))
        f.model.load(project)
        f.saved.noKey = noKey
        f.model.edit(f.model.connections[0])

        val test = checkNotNull(f.dialogs.tester).test(orders, null)
        f.runWorkers()

        val error = assertFailsWith<ExecutionException> { test.get() }
        assertEquals("The saved password could not be read: $reason", ConnectFailures.futureMessage(error))
        assertEquals(listOf(), f.testedWith)
    }

    @Test
    fun `while nothing can be saved, edit OK keeps the saved password and a typed one only goes into memory`() {
        val entry = savedOrders("old")
        f.saved.reason = "needs secret-tool"

        f.dialogs.edit = { ConnectionEdit(it, null, savePassword = false) }
        f.model.edit(entry)
        assertEquals("needs secret-tool", f.dialogs.editSaveReason)
        assertEquals(FakeSavedPasswords.value("o", "old"), stored()[0].savedPassword)

        f.dialogs.edit = { ConnectionEdit(it, "new".toCharArray(), savePassword = true) }
        f.model.edit(entry)
        f.runWorkers()
        assertEquals(FakeSavedPasswords.value("o", "old"), stored()[0].savedPassword)
        assertEquals(listOf(), f.saved.calls)
        assertEquals("new", String(checkNotNull(f.passwords.get(project, "o"))))
    }

    @Test
    fun `forgetting or unchecking while a typed password is encrypted wins over the late value`() {
        val entry = savedOrders("old")
        val typed = { cfg: ConnectionConfig -> ConnectionEdit(cfg, "new".toCharArray(), savePassword = true) }

        f.dialogs.edit = typed
        f.model.edit(entry)
        f.model.forgetSavedPassword(entry)
        f.runWorkers()
        assertNull(stored()[0].savedPassword, "forgotten")

        f.model.edit(entry)
        f.dialogs.edit = { ConnectionEdit(it, null, savePassword = false) }
        f.model.edit(entry)
        f.runWorkers()
        assertNull(stored()[0].savedPassword, "unchecked")
        assertEquals(2, f.saved.calls.size, "both were encrypted, neither applied")
    }

    @Test
    fun `a password that cannot be saved for a connection gone meanwhile says nothing`() {
        val entry = f.model.connections[0]
        f.saved.encryptFails = "needs secret-tool"
        f.dialogs.edit = { ConnectionEdit(it, "new".toCharArray(), savePassword = true) }
        f.model.edit(entry)

        f.model.load(project)
        f.runWorkers()

        assertEquals(listOf("encrypt o new"), f.saved.calls)
        assertEquals(listOf(), f.dialogs.errors)
    }
}
