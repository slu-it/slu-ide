package dev.sluide.databases.ui.tree

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.meta.DbObject
import dev.sluide.databases.meta.Introspector
import dev.sluide.databases.session.DbSession
import dev.sluide.databases.ui.FakeSession
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.CompletableFuture

/** A connection that fails on any call; the fake introspector never touches it. */
internal val unusedConnection: Connection =
    Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
        error("unexpected ${method.name}")
    } as Connection

/**
 * A session whose metadata work waits in [queue] until the test calls [runAll], then runs on the caller's thread with
 * [unusedConnection]; everything else is a [FakeSession].
 */
internal class MetadataSession(
    config: ConnectionConfig,
    private val delegate: FakeSession = FakeSession(config),
) : DbSession by delegate {
    val queue = mutableListOf<() -> Unit>()

    @Suppress("UNCHECKED_CAST")
    override fun <T> withMetadata(work: (Connection) -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        queue +=
            {
                try {
                    future.complete(work(unusedConnection))
                } catch (e: Throwable) {
                    future.completeExceptionally(e)
                }
            }
        return future
    }

    fun runAll() {
        while (queue.isNotEmpty()) queue.removeAt(0)()
    }
}

/** Scripted catalog; counts the calls. */
internal class FakeIntrospector : Introspector {
    var schemaNames = listOf("public", "sales")
    var contents = mutableMapOf<String, List<DbObject>>()
    var ddlText = "CREATE TABLE t ();\n"
    var failure: Throwable? = null
    val calls = mutableListOf<String>()

    override fun schemas(
        connection: Connection,
        includeSystem: Boolean,
    ): List<String> {
        calls += "schemas $includeSystem"
        failure?.let { throw it }
        return schemaNames + if (includeSystem) listOf("pg_catalog") else emptyList()
    }

    override fun schema(
        connection: Connection,
        schema: String,
    ): List<DbObject> {
        calls += "schema $schema"
        failure?.let { throw it }
        return contents[schema].orEmpty()
    }

    override fun ddl(
        connection: Connection,
        obj: DbObject,
    ): String {
        calls += "ddl ${obj.name}"
        return ddlText
    }

    override fun primaryKey(
        connection: Connection,
        schema: String?,
        table: String,
    ): List<String> {
        calls += "pk $table"
        return listOf("id")
    }
}
