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
            savedPassword = "v1:c2VjcmV0",
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
    fun `defaults are not written, and a password only as its saved value`() {
        store.save(projectFolder, listOf(minimal))

        val json = data.read(projectFolder)!!.get("databases").toString()
        assertEquals("""{"connections":[{"id":"b","name":"Local","kind":"POSTGRESQL"}]}""", json)
        store.save(projectFolder, listOf(full))
        val fullJson = data.read(projectFolder)!!.get("databases").toString()
        assertEquals(true, fullJson.contains("\"password\":\"v1:c2VjcmV0\""), fullJson)
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

    private fun storeDatabases(json: String) {
        data.update(projectFolder) { root -> root.set("databases", JsonMapper().readTree(json)) }
    }

    @Test
    fun `entries without a usable id or name are skipped`() {
        storeDatabases(
            """{"connections":["text",5,{"id":"  ","name":"Blank id","kind":"POSTGRESQL"},""" +
                """{"id":"n","name":"  ","kind":"POSTGRESQL"},{"id":"","name":"Empty id","kind":"POSTGRESQL"},""" +
                """{"id":7,"name":"Number id","kind":"POSTGRESQL"},{"id":"m","name":8,"kind":"POSTGRESQL"},""" +
                """{"id":"k","name":"No kind"},{"id":"k","name":"Kind number","kind":1},""" +
                """{"id":"b","name":"Local","kind":"POSTGRESQL"},{"id":"b","name":"Twin","kind":"MARIADB"}]}""",
        )

        assertEquals(listOf(minimal), store.load(projectFolder))
    }

    @Test
    fun `values of the wrong type fall back to the defaults`() {
        storeDatabases(
            """{"connections":[{"id":"b","name":"Local","kind":"POSTGRESQL","host":5,"port":1.5,"database":true,""" +
                """"user":[],"readOnly":"yes","commitMode":"NOPE","sslMode":3,"caFile":5,"customUrl":{},""" +
                """"driverProperties":{"a":"b","c":1},"color":"NOPE","showSystemSchemas":"x","password":5}]}""",
        )

        assertEquals(listOf(minimal.copy(driverProperties = mapOf("a" to "b"))), store.load(projectFolder))
    }

    @Test
    fun `a port too large for an int and driverProperties that are no object fall back`() {
        storeDatabases(
            """{"connections":[{"id":"b","name":"Local","kind":"POSTGRESQL","port":99999999999,""" +
                """"driverProperties":["a"]},{"id":"c","name":"Third","kind":"POSTGRESQL","port":"5433"}]}""",
        )

        assertEquals(listOf(minimal, minimal.copy(id = "c", name = "Third")), store.load(projectFolder))
    }

    @Test
    fun `an integral port and boolean flags are read`() {
        storeDatabases(
            """{"connections":[{"id":"b","name":"Local","kind":"POSTGRESQL","port":6000,"readOnly":true,""" +
                """"showSystemSchemas":true}]}""",
        )

        assertEquals(
            listOf(minimal.copy(port = 6000, readOnly = true, showSystemSchemas = true)),
            store.load(projectFolder),
        )
    }

    @Test
    fun `databases or connections of another type load as none`() {
        storeDatabases("[1]")
        assertEquals(emptyList(), store.load(projectFolder))
        storeDatabases("""{"connections":{"x":{"id":"b","name":"Local","kind":"POSTGRESQL"}}}""")
        assertEquals(emptyList(), store.load(projectFolder))
        storeDatabases("""{"future":1}""")
        assertEquals(emptyList(), store.load(projectFolder))
    }

    @Test
    fun `connections that are no array are replaced on save`() {
        storeDatabases("""{"connections":{"x":{"id":"q","name":"Hidden","kind":"POSTGRESQL"}}}""")

        assertTrue(store.save(projectFolder, listOf(minimal)))

        val entries = data.read(projectFolder)!!.get("databases").get("connections")
        assertEquals(listOf("b"), entries.values().map { it.get("id").stringValue() })
    }

    @Test
    fun `a databases field that is no object is replaced on save`() {
        storeDatabases("[1]")

        assertTrue(store.save(projectFolder, listOf(minimal)))

        assertEquals(listOf(minimal), store.load(projectFolder))
    }

    @Test
    fun `unknown keys of an entry stay with the entry of the same id only`() {
        storeDatabases(
            """{"connections":[{"id":"b","name":"Old","kind":"POSTGRESQL","extra":"keep"},""" +
                """{"id":"c","name":"Other","kind":"POSTGRESQL","other":"drop"}]}""",
        )

        assertTrue(store.save(projectFolder, listOf(full, minimal)))

        val entries = data.read(projectFolder)!!.get("databases").get("connections")
        assertNull(entries.get(0).get("extra"))
        assertNull(entries.get(0).get("other"))
        assertEquals("keep", entries.get(1).get("extra").stringValue())
        assertEquals(2, entries.size())
    }

    @Test
    fun `known keys of the old entry are not copied over the new one`() {
        storeDatabases(
            """{"connections":[{"id":"b","name":"Local","kind":"POSTGRESQL","host":"old","readOnly":true}]}""",
        )

        assertTrue(store.save(projectFolder, listOf(minimal)))

        assertEquals(listOf(minimal), store.load(projectFolder))
        assertNull(
            data
                .read(projectFolder)!!
                .get("databases")
                .get("connections")
                .get(0)
                .get("host"),
        )
    }

    @Test
    fun `a save that fails while building is logged and gives false`() {
        val failing =
            object : AbstractList<ConnectionConfig>() {
                override val size get() = 1

                override fun get(index: Int): ConnectionConfig = throw IllegalStateException("boom")
            }

        assertEquals(false, store.save(projectFolder, failing))
        assertEquals(false, store.save(null, failing))
    }
}
