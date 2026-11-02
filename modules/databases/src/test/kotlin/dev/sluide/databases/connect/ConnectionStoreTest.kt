package dev.sluide.databases.connect

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionStoreTest {
    @TempDir
    lateinit var dir: Path

    private val projectFolder by lazy { dir.resolve("project").createDirectories() }
    private val data by lazy { ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()) }
    private val store by lazy { ConnectionStore(data) }

    private val full =
        ConnectionConfig(
            id = "a",
            name = "Prod",
            kind = DatabaseKind.MARIADB,
            host = "db.example.com",
            port = 3307,
            database = "shop",
            user = "app",
            readOnly = true,
            commitMode = CommitMode.MANUAL,
            sslMode = SslMode.VERIFY_FULL,
            caFile = "/etc/ca.pem",
            customUrl = "jdbc:mariadb://x/y",
            driverProperties = mapOf("connectTimeout" to "5000"),
            color = CardColor.RED,
            showSystemSchemas = true,
        )
    private val minimal = ConnectionConfig("b", "Local", DatabaseKind.POSTGRESQL)

    @Test
    fun `nothing stored gives no connections`() {
        assertEquals(emptyList(), store.load(projectFolder))
        assertEquals(emptyList(), store.load(null))
    }

    @Test
    fun `connections survive a round trip in their order`() {
        assertTrue(store.save(projectFolder, listOf(minimal, full)))

        assertEquals(listOf(minimal, full), ConnectionStore(data).load(projectFolder))

        store.save(projectFolder, listOf(full, minimal))
        assertEquals(listOf(full, minimal), store.load(projectFolder))
    }

    @Test
    fun `defaults are not written and no password is`() {
        store.save(projectFolder, listOf(minimal))

        val json = data.read(projectFolder)!!.get("databases").toString()
        assertEquals("""{"connections":[{"id":"b","name":"Local","kind":"POSTGRESQL"}]}""", json)
        store.save(projectFolder, listOf(full))
        val fullJson = data.read(projectFolder)!!.get("databases").toString()
        assertEquals(false, fullJson.contains("password", ignoreCase = true))
        assertEquals(true, fullJson.contains("\"sslMode\":\"VERIFY_FULL\""))
    }

    @Test
    fun `saving no connections removes the field`() {
        store.save(projectFolder, listOf(minimal))
        store.save(projectFolder, emptyList())

        assertNull(data.read(projectFolder)?.get("databases"))
    }

    @Test
    fun `unknown keys survive a rewrite`() {
        data.update(projectFolder) { root ->
            root.set(
                "databases",
                JsonMapper().readTree(
                    """{"future":1,"connections":[{"id":"b","name":"Old","kind":"POSTGRESQL","extra":"keep"}]}""",
                ),
            )
        }

        store.save(projectFolder, listOf(store.load(projectFolder).single().copy(name = "New")))

        val databases = data.read(projectFolder)!!.get("databases")
        assertEquals(1, databases.get("future").intValue())
        assertEquals(
            "keep",
            databases
                .get("connections")
                .get(0)
                .get("extra")
                .stringValue(),
        )
        assertEquals(
            "New",
            databases
                .get("connections")
                .get(0)
                .get("name")
                .stringValue(),
        )
    }

    @Test
    fun `an entry with an unknown kind or without id is skipped on load and kept on save`() {
        data.update(projectFolder) { root ->
            root.set(
                "databases",
                JsonMapper().readTree(
                    """{"connections":[{"id":"x","name":"Oracle","kind":"ORACLE"},""" +
                        """{"name":"no id","kind":"POSTGRESQL"},{"id":"b","name":"Local","kind":"POSTGRESQL"}]}""",
                ),
            )
        }

        assertEquals(listOf(minimal), store.load(projectFolder))

        store.save(projectFolder, listOf(minimal, full))

        val entries = data.read(projectFolder)!!.get("databases").get("connections")
        assertEquals(listOf("b", "a", "x", null), entries.values().map { it.get("id")?.stringValue() })
        assertEquals("ORACLE", entries.get(2).get("kind").stringValue())
    }

    @Test
    fun `without a folder the shared data is used`() {
        store.save(null, listOf(full))

        assertEquals(listOf(full), store.load(null))
        assertEquals(emptyList(), store.load(projectFolder))
    }
}
