package dev.sluide.games.doubles

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.projectdata.ProjectDataStore
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * The last board size and the best scores of Doubles, user-wide: in the top-level field `doubles` of `shared.json`,
 * `{"size": 4, "best": {"3": 812, "4": 20512}}` (sides as keys). Read once on first use, then kept in memory; changes
 * apply at once and are written off the FX thread, saves within [writeDelay] of each other ending in one write.
 * [destroy] writes a pending change at once, and so does every later change. A missing or invalid value reads as its
 * default; other fields of `doubles` and other keys of `best` stay as they are, a `doubles` that is no JSON object is
 * replaced.
 */
@Component
class DoublesPreferences internal constructor(
    private val store: ProjectDataStore,
    private val writeDelay: Duration,
    private val writer: Executor,
) : DisposableBean {
    @Autowired
    constructor(
        store: ProjectDataStore,
    ) : this(store, Duration.ofMillis(300), NamedThreads.single("sluide-doubles-prefs"))

    private data class Values(
        val size: BoardSize,
        val best: Map<BoardSize, Int>,
    )

    @Volatile
    private var current: Values? = null
    private val pending = AtomicReference<Values?>()
    private val changeLock = Any()

    /** Held while taking and writing the pending values, so that [destroy] waits for a write in progress. */
    private val writeLock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var warned = false

    /** The size of the last game; 4×4 unless saved otherwise. */
    internal val lastSize: BoardSize get() = values().size

    internal fun saveSize(size: BoardSize) {
        change { it.copy(size = size) }
    }

    /** The best score of [size]; 0 when there is none. */
    internal fun best(size: BoardSize): Int = values().best[size] ?: 0

    /** Stores [score] as the best of [size] when it is greater than the stored one; true when stored. */
    internal fun recordScore(
        size: BoardSize,
        score: Int,
    ): Boolean {
        var stored = false
        change {
            if (score > (it.best[size] ?: 0)) {
                stored = true
                it.copy(best = it.best + (size to score))
            } else {
                it
            }
        }
        return stored
    }

    /** The app quits: the writer is a daemon thread, so pending values are written now; later changes write at once. */
    override fun destroy() {
        closed = true
        flush()
    }

    private fun change(transform: (Values) -> Values) {
        var before: Values? = null
        val values =
            synchronized(changeLock) {
                before = values()
                transform(values()).also { current = it }
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

    private fun flush() {
        synchronized(writeLock) { pending.getAndSet(null)?.let(::write) }
    }

    private fun values(): Values = current ?: read().also { current = it }

    private fun read(): Values {
        val root = store.readShared()?.get(FIELD) as? ObjectNode ?: return DEFAULT
        val size = root.get(SIZE)?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()
        val best =
            (root.get(BEST) as? ObjectNode)?.let { node ->
                BoardSize.entries
                    .mapNotNull { size ->
                        node
                            .get(size.side.toString())
                            ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
                            ?.asInt()
                            ?.takeIf { it > 0 }
                            ?.let { size to it }
                    }.toMap()
            } ?: emptyMap()
        return Values(size?.let(BoardSize::ofSide) ?: BoardSize.DEFAULT, best)
    }

    private fun write(values: Values) {
        val written =
            store.updateShared { root ->
                val node = root.get(FIELD) as? ObjectNode ?: root.objectNode()
                node.put(SIZE, values.size.side)
                val best = node.get(BEST) as? ObjectNode ?: root.objectNode()
                values.best.forEach { (size, score) -> best.put(size.side.toString(), score) }
                node.set(BEST, best)
                root.set(FIELD, node)
            }
        if (!written && !warned) {
            warned = true
            System
                .getLogger(DoublesPreferences::class.java.name)
                .log(System.Logger.Level.WARNING, "Doubles settings not saved: the shared data file cannot be written")
        }
    }

    private companion object {
        const val FIELD = "doubles"
        const val SIZE = "size"
        const val BEST = "best"

        val DEFAULT = Values(BoardSize.DEFAULT, emptyMap())
    }
}
