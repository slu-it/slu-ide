package dev.sluide.games.common

import dev.sluide.projectdata.ProjectDataStore
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * The values [V] of one game, user-wide: in the top-level field [fieldName] of `shared.json`. Read once on first use
 * through [read] (which gets the field's object, or null when it is missing or no JSON object), then kept in memory;
 * changes apply at once and are written off the FX thread, changes within [writeDelay] of each other ending in one
 * write. [destroy] writes a pending change at once, and so does every later change. [write] puts the values into the
 * field's object, which keeps the other keys it has; a [fieldName] that is no JSON object is replaced. [gameName] is the
 * name in the log text when writing fails. [V] needs value equality: a change to an equal value writes nothing.
 *
 * Not a Spring bean: the game's preferences component delegates to it.
 */
internal class StoredField<V : Any>(
    private val store: ProjectDataStore,
    private val fieldName: String,
    private val gameName: String,
    private val writeDelay: Duration,
    private val writer: Executor,
    private val read: (ObjectNode?) -> V,
    private val write: (values: V, node: ObjectNode) -> Unit,
) {
    @Volatile
    private var current: V? = null
    private val pending = AtomicReference<V?>()
    private val changeLock = Any()

    /** Held while taking and writing the pending values, so that [destroy] waits for a write in progress. */
    private val writeLock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var warned = false

    /** The values; read from the file on first use. */
    val value: V get() = current ?: read(store.readShared()?.get(fieldName) as? ObjectNode).also { current = it }

    /** Replaces the values by [transform] of them; it runs once, under a lock. */
    fun change(transform: (V) -> V) {
        var before: V? = null
        val values =
            synchronized(changeLock) {
                before = value
                transform(value).also { current = it }
            }
        if (values == before) return
        if (closed) {
            pending.set(values)
            flush()
        } else if (pending.getAndSet(values) == null) {
            writer.execute {
                // Changes arriving meanwhile replace the pending values; this task writes the latest of them.
                try {
                    Thread.sleep(writeDelay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                flush()
            }
        }
    }

    /** The app quits: the writer is a daemon thread, so pending values are written now; later changes write at once. */
    fun destroy() {
        closed = true
        flush()
    }

    private fun flush() {
        synchronized(writeLock) { pending.getAndSet(null)?.let(::writeOut) }
    }

    private fun writeOut(values: V) {
        val written =
            store.updateShared { root ->
                val node = root.get(fieldName) as? ObjectNode ?: root.objectNode()
                write(values, node)
                root.set(fieldName, node)
            }
        if (!written && !warned) {
            warned = true
            System
                .getLogger(StoredField::class.java.name)
                .log(
                    System.Logger.Level.WARNING,
                    "$gameName settings not saved: the shared data file cannot be written",
                )
        }
    }
}
