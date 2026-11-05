package dev.sluide.fileviewers

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.projectdata.ProjectDataStore
import javafx.beans.property.BooleanProperty
import javafx.beans.property.SimpleBooleanProperty
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * Whether the file editors wrap long lines (ADR 0130): one user-wide switch, `{"softWrap": true}` in the top-level field
 * `editor` of `shared.json` (ADR 0101). Every code view binds to [enabled] through the window service, so toggling it
 * in one editor wraps or unwraps all of them, in every window.
 *
 * Read once on first use of [enabled]. Setting it writes off the FX thread; changes within [writeDelay] of each other
 * end in one write of the latest value. When the app quits ([destroy]) a pending write is done at once, and so is every
 * later change. Only `true` is written: off removes the key, and an `editor` left empty goes too; other fields of
 * `editor` stay. A missing or invalid value, or an `editor` that is no JSON object, reads as off.
 */
@Component
class SoftWrap internal constructor(
    private val store: ProjectDataStore,
    private val writeDelay: Duration,
    private val writer: Executor,
) : DisposableBean {
    @Autowired
    constructor(
        store: ProjectDataStore,
    ) : this(store, Duration.ofMillis(300), NamedThreads.single("sluide-soft-wrap"))

    private val pending = AtomicReference<Boolean?>()

    /** Held while taking and writing the pending value, so that [destroy] waits for a write in progress. */
    private val writeLock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var warned = false

    /**
     * Whether file editors wrap long lines; FX thread only. Read from shared.json on first access; setting it saves
     * shortly after, off the FX thread.
     */
    val enabled: BooleanProperty by lazy(LazyThreadSafetyMode.NONE) {
        SimpleBooleanProperty(this, "enabled", read()).apply { addListener { _, _, on -> save(on) } }
    }

    /** The app quits: the writer is a daemon thread, so a pending value is written now; later saves write at once. */
    override fun destroy() {
        closed = true
        flush()
    }

    private fun save(on: Boolean) {
        if (closed) {
            pending.set(on)
            flush()
        } else if (pending.getAndSet(on) == null) {
            writer.execute {
                // Changes arriving meanwhile replace the pending value; this task writes the latest of them.
                try {
                    Thread.sleep(writeDelay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                flush()
            }
        }
    }

    private fun flush() {
        synchronized(writeLock) { pending.getAndSet(null)?.let(::write) }
    }

    private fun read(): Boolean {
        val editor = store.readShared()?.get(FIELD) as? ObjectNode ?: return false
        return editor.get(SOFT_WRAP)?.takeIf { it.isBoolean }?.asBoolean() ?: false
    }

    private fun write(on: Boolean) {
        val written =
            store.updateShared { root ->
                val editor = root.get(FIELD) as? ObjectNode
                if (on) {
                    root.set(FIELD, (editor ?: root.objectNode()).put(SOFT_WRAP, true))
                } else if (editor != null) {
                    editor.remove(SOFT_WRAP)
                    if (editor.isEmpty) root.remove(FIELD)
                }
            }
        if (!written && !warned) {
            warned = true
            System
                .getLogger(SoftWrap::class.java.name)
                .log(System.Logger.Level.WARNING, "Soft wrap not saved: the shared data file cannot be written")
        }
    }

    private companion object {
        const val FIELD = "editor"
        const val SOFT_WRAP = "softWrap"
    }
}
