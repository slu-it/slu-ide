package dev.sluide.databases.ui.tree

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.meta.DbObject
import dev.sluide.databases.meta.ObjectKind
import dev.sluide.databases.ui.ConnectionEntry
import org.junit.jupiter.api.BeforeEach
import java.sql.SQLException
import java.util.concurrent.CompletionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MetadataCacheTest {
    private val config = ConnectionConfig("o", "orders", DatabaseKind.POSTGRESQL)
    private val entry = ConnectionEntry(config, "|o")
    private val introspector = FakeIntrospector()
    private lateinit var session: MetadataSession
    private lateinit var cache: MetadataCache

    private val orders =
        DbObject(
            ObjectKind.TABLE,
            "public",
            "orders",
            children = listOf(DbObject(ObjectKind.COLUMN, "public", "id", table = "orders")),
        )

    @BeforeEach
    fun setUp() {
        introspector.contents["public"] =
            listOf(
                DbObject(ObjectKind.GROUP, "public", "tables", children = listOf(orders)),
                DbObject(
                    ObjectKind.GROUP,
                    "public",
                    "functions",
                    children = listOf(DbObject(ObjectKind.FUNCTION, "public", "f")),
                ),
            )
        session = MetadataSession(config)
        entry.connected(session)
        cache = MetadataCache(entry, { it.run() }, { introspector }, { _, _ -> "public" })
        session.runAll()
    }

    @Test
    fun `a schema is loaded once and then served from the cache`() {
        val first = cache.schema("public")
        session.runAll()
        assertEquals(introspector.contents["public"], first.get())
        assertTrue(cache.schema("public").isDone)
        assertEquals(1, introspector.calls.count { it == "schema public" })
    }

    @Test
    fun `concurrent requests share one in-flight load`() {
        val a = cache.schema("public")
        val b = cache.schema("public")
        assertSame(a, b)
        session.runAll()
        assertEquals(1, introspector.calls.count { it == "schema public" })
    }

    @Test
    fun `schemas are cached per include-system flag`() {
        cache.schemas(false)
        cache.schemas(true)
        session.runAll()
        assertEquals(listOf("schemas false", "schemas true"), introspector.calls)
        assertEquals(listOf("public", "sales"), cache.schemas(false).get())
    }

    @Test
    fun `refresh drops the cache and replies of loads started before it`() {
        val stale = cache.schema("public")
        cache.refresh()
        session.runAll()
        assertTrue(stale.isDone)
        // The stale reply was not stored: the next request loads again.
        cache.schema("public")
        session.runAll()
        assertEquals(2, introspector.calls.count { it == "schema public" })
        cache.refresh()
        cache.schema("public")
        session.runAll()
        assertEquals(3, introspector.calls.count { it == "schema public" })
    }

    @Test
    fun `a new session clears the cache and loads the default schema again`() {
        cache.schema("public")
        session.runAll()
        assertEquals("public", cache.defaultSchema.get())
        val next = MetadataSession(config)
        entry.connected(next)
        assertEquals("", cache.defaultSchema.get())
        cache.schema("public")
        next.runAll()
        assertEquals(2, introspector.calls.count { it == "schema public" })
        assertEquals("public", cache.defaultSchema.get())
    }

    @Test
    fun `without a session the futures fail with Not connected`() {
        entry.ended(null)
        val failure = assertFailsWith<CompletionException> { cache.schema("public").join() }
        assertEquals("Not connected", failure.cause?.message)
        assertFailsWith<CompletionException> { cache.ddl(orders).join() }
    }

    @Test
    fun `a failed load fails the future with the cause and is retried on the next request`() {
        introspector.failure = SQLException("boom")
        val failed = cache.schema("public")
        session.runAll()
        assertEquals("boom", assertFailsWith<CompletionException> { failed.join() }.cause?.message)
        introspector.failure = null
        val retry = cache.schema("public")
        session.runAll()
        assertEquals(2, retry.get().size)
    }

    @Test
    fun `ddl is read fresh and primary keys are cached`() {
        assertEquals("CREATE TABLE t ();\n", cache.ddl(orders).also { session.runAll() }.get())
        cache.ddl(orders)
        session.runAll()
        assertEquals(2, introspector.calls.count { it == "ddl orders" })
        cache.primaryKey("public", "orders")
        session.runAll()
        assertEquals(listOf("id"), cache.primaryKey("public", "orders").get())
        assertEquals(1, introspector.calls.count { it == "pk orders" })
    }

    @Test
    fun `the catalog misses start a load, fire onLoaded and then answer from the cache`() {
        var loaded = 0
        val subscription = cache.onLoaded { loaded++ }
        assertEquals(emptyList(), cache.catalog.tables("public"))
        assertEquals(emptyList(), cache.catalog.tables("public"))
        session.runAll()
        assertEquals(1, introspector.calls.count { it == "schema public" })
        assertEquals(1, loaded)
        assertEquals(listOf("orders"), cache.catalog.tables("public"))
        assertEquals(listOf("id"), cache.catalog.columns("public", "orders"))
        assertEquals(emptyList(), cache.catalog.columns("public", "missing"))
        subscription.unsubscribe()
        cache.catalog.schemas()
        session.runAll()
        assertEquals(1, loaded)
        assertEquals(listOf("public", "sales"), cache.catalog.schemas())
    }

    @Test
    fun `the catalog resolves the default schema for a null schema`() {
        assertEquals("public", cache.catalog.defaultSchema)
        cache.catalog.tables(null)
        session.runAll()
        assertEquals(listOf("orders"), cache.catalog.tables(null))
    }

    @Test
    fun `the catalog does not retry a failed load until refresh`() {
        introspector.failure = SQLException("down")
        cache.catalog.tables("public")
        session.runAll()
        cache.catalog.tables("public")
        assertTrue(session.queue.isEmpty())
        cache.refresh()
        cache.catalog.tables("public")
        assertEquals(1, session.queue.size)
    }
}
