package dev.sluide.databases

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.ConnectionStore
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.connect.PasswordCache
import dev.sluide.databases.session.DbSession
import dev.sluide.databases.settings.DatabasesSettings
import dev.sluide.databases.sql.QueryHistory
import dev.sluide.databases.ui.FakeDialogs
import dev.sluide.databases.ui.FakeSavedPasswords
import dev.sluide.databases.ui.FakeSession
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The window bean without a toolkit: Test Connection, the folder's connections and the settings sessions read. */
class DatabasesTest {
    @TempDir
    lateinit var dir: Path

    private val config =
        ConnectionConfig("o", "orders", DatabaseKind.POSTGRESQL, customUrl = "jdbc:postgresql://app:hunter22@db/orders")
    private val data by lazy { ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()) }
    private val store by lazy { ConnectionStore(data) }
    private val settings by lazy { DatabasesSettings(SettingsStore(dir.resolve("settings.properties"))) }

    /** What [connect] does: returns a connection or throws. */
    private var connect: (ConnectionConfig, CharArray?) -> Connection = { _, _ -> error("not connected") }

    /** The limits each opened session was given: timeout and cap suppliers. */
    private val opened = mutableListOf<Pair<() -> Int, () -> Int>>()

    private val dialogs = FakeDialogs()
    private val saved = FakeSavedPasswords()

    private val databases by lazy {
        Databases(
            store,
            PasswordCache(),
            QueryHistory(),
            settings,
            BackgroundTasks.forTests(),
            { dialogs },
            { c, p -> connect(c, p) },
            { it.run() },
            { c, _, _, _, timeout, cap ->
                opened += timeout to cap
                FakeSession(c) as DbSession
            },
            savedPasswords = saved,
        )
    }

    @Test
    fun `Test Connection reads product and version and closes the connection`() {
        var closed = false
        connect = { _, _ -> connection(onClose = { closed = true }) }

        val result = databases.tester().test(config, "pw".toCharArray()).get(10, TimeUnit.SECONDS)

        assertEquals("PostgreSQL 17.2", result)
        assertTrue(closed)
    }

    @Test
    fun `a failed Test Connection carries the cleaned message, without URL credentials or the password`() {
        connect = { _, _ ->
            throw SQLException("No suitable driver found for jdbc:postgresql://app:hunter22@db/orders (hunter22)")
        }

        val error =
            assertFailsWith<ExecutionException> {
                databases.tester().test(config, "hunter22".toCharArray()).get(10, TimeUnit.SECONDS)
            }

        assertEquals("No suitable driver found for jdbc:postgresql://db/orders (********)", error.cause?.message)
        assertFalse(
            error.cause
                ?.message
                .orEmpty()
                .contains("hunter22"),
        )
    }

    @Test
    fun `the connections follow the project folder`() {
        val project = dir.resolve("project").createDirectories()
        store.save(null, listOf(config))
        store.save(project, listOf(config.copy(id = "p", name = "project db")))

        val model = databases.model()
        assertEquals(listOf("orders"), model.connections.map { it.config.get().name })

        databases.projectFolder.set(project)

        assertEquals(listOf("project db"), model.connections.map { it.config.get().name })
        assertEquals(project, model.folder)
    }

    @Test
    fun `the connections' dialogs get the saved passwords`() {
        saved.reason = "needs secret-tool"

        databases.model().add(DatabaseKind.POSTGRESQL)

        assertEquals("needs secret-tool", dialogs.editSaveReason)
    }

    @Test
    fun `sessions read the current statement timeout and fetch-all cap`() {
        databases.openSession(config, null)
        val (timeout, cap) = opened.single()
        assertEquals(0, timeout())
        assertEquals(100_000, cap())

        settings.apply(500, 20_000, 30)

        assertEquals(30, timeout())
        assertEquals(20_000, cap())
    }

    private fun connection(onClose: () -> Unit): Connection {
        val meta =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DatabaseMetaData::class.java)) { _, method, _ ->
                when (method.name) {
                    "getDatabaseProductName" -> "PostgreSQL"
                    "getDatabaseProductVersion" -> "17.2"
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as DatabaseMetaData
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "getMetaData" -> meta
                "close" -> onClose()
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Connection
    }
}
