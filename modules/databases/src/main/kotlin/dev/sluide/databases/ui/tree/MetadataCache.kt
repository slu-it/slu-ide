package dev.sluide.databases.ui.tree

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.meta.DbObject
import dev.sluide.databases.meta.Introspector
import dev.sluide.databases.meta.ObjectKind
import dev.sluide.databases.sql.CompletionCatalog
import dev.sluide.databases.ui.ConnectionEntry
import javafx.application.Platform
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.util.Subscription
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * The catalog of one connection as the tree and the completion read it: schemas, a schema's objects, DDL and primary
 * keys, loaded on the session's metadata connection and cached until [refresh] or a new session. Concurrent requests
 * for the same thing share one future. Called on the FX thread only; futures complete on it (through [fx]).
 */
class MetadataCache internal constructor(
    private val entry: ConnectionEntry,
    private val fx: (Runnable) -> Unit = Platform::runLater,
    private val introspector: (DatabaseKind) -> Introspector = { Introspector.of(it) },
    private val defaultSchemaQuery: (Connection, DatabaseKind) -> String? = ::queryDefaultSchema,
) {
    private data class SchemasKey(
        val includeSystem: Boolean,
    )

    private data class SchemaKey(
        val name: String,
    )

    private data class PrimaryKeyKey(
        val schema: String?,
        val table: String,
    )

    /** Bumped by every clear, so replies of loads started before it are not stored. */
    private var generation = 0
    private val results = HashMap<Any, Any>()
    private val inFlight = HashMap<Any, CompletableFuture<*>>()

    /** Keys whose last load failed: the catalog does not retry them on every keystroke, only after a refresh. */
    private val failed = HashSet<Any>()
    private val listeners = ArrayList<() -> Unit>()
    private val defaultSchemaWrapper = ReadOnlyStringWrapper("")

    /** The default schema: PostgreSQL `current_schema()`, MariaDB `DATABASE()`; loaded once per session, "" before. */
    val defaultSchema: ReadOnlyStringProperty = defaultSchemaWrapper.readOnlyProperty

    init {
        entry.session.addListener { _, _, session ->
            clear()
            defaultSchemaWrapper.set("")
            if (session != null) loadDefaultSchema()
        }
        if (entry.session.get() != null) loadDefaultSchema()
    }

    /** Schema names; cached until refresh or a new session. Completes on the FX thread. */
    fun schemas(includeSystem: Boolean): CompletableFuture<List<String>> =
        cached(SchemasKey(includeSystem)) { i, c -> i.schemas(c, includeSystem) }

    /** A schema's groups and objects; cached. Completes on the FX thread. */
    fun schema(name: String): CompletableFuture<List<DbObject>> = cached(SchemaKey(name)) { i, c -> i.schema(c, name) }

    /** The DDL of [obj], read fresh every time; "" when the object is gone. */
    fun ddl(obj: DbObject): CompletableFuture<String> {
        val out = CompletableFuture<String>()
        start({ i, c -> i.ddl(c, obj) }) { value, error ->
            if (error == null) out.complete(value) else out.completeExceptionally(error)
        }
        return out
    }

    /** The primary-key columns of a table in key order; cached. */
    fun primaryKey(
        schema: String?,
        table: String,
    ): CompletableFuture<List<String>> = cached(PrimaryKeyKey(schema, table)) { i, c -> i.primaryKey(c, schema, table) }

    /** Drops everything cached (Refresh); a new session drops it too. */
    fun refresh() = clear()

    /** Non-blocking view for completion: returns what is cached; a miss starts a load, and [onLoaded] fires after it. */
    val catalog: CompletionCatalog =
        object : CompletionCatalog {
            override val defaultSchema: String? get() = defaultSchemaWrapper.get().ifEmpty { null }

            override fun schemas(): List<String> {
                val include = entry.config.get().showSystemSchemas
                return cachedOrLoad(SchemasKey(include)) { schemas(include) } ?: emptyList()
            }

            override fun tables(schema: String?): List<String> = tableObjects(schema).map { it.name }

            override fun columns(
                schema: String?,
                table: String,
            ): List<String> =
                tableObjects(schema)
                    .firstOrNull { it.name == table }
                    ?.children
                    .orEmpty()
                    .filter { it.kind == ObjectKind.COLUMN }
                    .map { it.name }

            private fun tableObjects(schema: String?): List<DbObject> {
                val name = schema ?: defaultSchema ?: return emptyList()
                val objects = cachedOrLoad<List<DbObject>>(SchemaKey(name)) { schema(name) } ?: return emptyList()
                return objects
                    .flatMap {
                        if (it.kind ==
                            ObjectKind.GROUP
                        ) {
                            it.children
                        } else {
                            listOf(it)
                        }
                    }.filter { it.kind in TABLE_LIKE }
            }
        }

    /** Fires (FX thread) when a load finished, so an open completion popup can refresh. */
    fun onLoaded(listener: () -> Unit): Subscription {
        listeners += listener
        return Subscription { listeners -= listener }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cachedOrLoad(
        key: Any,
        load: () -> CompletableFuture<*>,
    ): T? {
        results[key]?.let { return it as T }
        if (key !in failed && key !in inFlight) load().exceptionally { null }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(
        key: Any,
        work: (Introspector, Connection) -> T,
    ): CompletableFuture<T> {
        results[key]?.let { return CompletableFuture.completedFuture(it as T) }
        (inFlight[key] as CompletableFuture<T>?)?.let { return it }
        val out = CompletableFuture<T>()
        val started = generation
        inFlight[key] = out
        start(work) { value, error ->
            val current = started == generation
            if (current) {
                inFlight.remove(key)
                if (error == null) results[key] = value as Any else failed += key
            }
            if (error == null) out.complete(value) else out.completeExceptionally(error)
            if (error == null && current) fireLoaded()
        }
        return out
    }

    /** Runs [work] on the metadata connection and calls [done] on the FX thread with its result or its failure. */
    private fun <T> start(
        work: (Introspector, Connection) -> T,
        done: (T?, Throwable?) -> Unit,
    ) {
        val session = entry.session.get()
        if (session == null) {
            done(null, IllegalStateException("Not connected"))
            return
        }
        val kind = entry.config.get().kind
        val raw =
            try {
                session.withMetadata { work(introspector(kind), it) }
            } catch (e: Throwable) {
                done(null, e)
                return
            }
        raw.whenComplete { value, error -> fx(Runnable { done(value, error?.let(::unwrap)) }) }
    }

    private fun loadDefaultSchema() {
        val session = entry.session.get() ?: return
        val kind = entry.config.get().kind
        start({ _, c -> defaultSchemaQuery(c, kind) }) { value, error ->
            if (error == null && entry.session.get() === session) {
                defaultSchemaWrapper.set(value.orEmpty())
                fireLoaded()
            }
        }
    }

    private fun clear() {
        generation++
        results.clear()
        inFlight.clear()
        failed.clear()
    }

    private fun fireLoaded() = listeners.toList().forEach { it() }

    private fun unwrap(error: Throwable): Throwable {
        var t = error
        while ((t is CompletionException || t is ExecutionException) && t.cause != null) t = checkNotNull(t.cause)
        return t
    }

    private companion object {
        val TABLE_LIKE = setOf(ObjectKind.TABLE, ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW)
    }
}

private fun queryDefaultSchema(
    connection: Connection,
    kind: DatabaseKind,
): String? {
    val sql = if (kind == DatabaseKind.MARIADB) "SELECT DATABASE()" else "SELECT current_schema()"
    return connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null }
    }
}
