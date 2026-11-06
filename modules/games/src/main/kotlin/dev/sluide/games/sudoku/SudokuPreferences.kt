package dev.sluide.games.sudoku

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
 * The last size and level and the best times of Sudoku, user-wide: in the top-level field `sudoku` of `shared.json`,
 * `{"size": 9, "level": "medium", "best": {"9-hard": 754000}}` (best keys `<side>-<level key>`, values in
 * milliseconds). Read once on first use, then kept in memory; changes apply at once and are written off the FX thread,
 * saves within [writeDelay] of each other ending in one write. [destroy] writes a pending change at once, and so does
 * every later change. A missing or invalid value reads as its default; other fields of `sudoku` and other keys of
 * `best` stay as they are, a `sudoku` that is no JSON object is replaced.
 */
@Component
class SudokuPreferences internal constructor(
    private val store: ProjectDataStore,
    private val writeDelay: Duration,
    private val writer: Executor,
) : DisposableBean {
    @Autowired
    constructor(
        store: ProjectDataStore,
    ) : this(store, Duration.ofMillis(300), NamedThreads.single("sluide-sudoku-prefs"))

    private data class Values(
        val size: SudokuSize,
        val level: Level,
        val best: Map<String, Long>,
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

    /** The size of the last puzzle; 9×9 unless saved otherwise. */
    internal val lastSize: SudokuSize get() = values().size

    /** The level of the last puzzle; Medium unless saved otherwise. */
    internal val lastLevel: Level get() = values().level

    internal fun save(
        size: SudokuSize,
        level: Level,
    ) {
        change { it.copy(size = size, level = level) }
    }

    /** The best time in milliseconds of [size] and [level]; null when there is none. */
    internal fun best(
        size: SudokuSize,
        level: Level,
    ): Long? = values().best[bestKey(size, level)]

    /** Stores [millis] as the best of [size] and [level] when it is lower than the stored one; true when stored. */
    internal fun recordTime(
        size: SudokuSize,
        level: Level,
        millis: Long,
    ): Boolean {
        var stored = false
        val key = bestKey(size, level)
        change {
            val old = it.best[key]
            if (millis > 0 && (old == null || millis < old)) {
                stored = true
                it.copy(best = it.best + (key to millis))
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
        val size =
            root
                .get(SIZE)
                ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
                ?.asInt()
                ?.let { SudokuSize.ofKey(it.toString()) }
        val level =
            root
                .get(LEVEL)
                ?.takeIf { it.isString }
                ?.asString()
                ?.let(Level::ofKey)
        val best =
            (root.get(BEST) as? ObjectNode)?.let { node ->
                SudokuSize.entries
                    .flatMap { s -> Level.entries.map { l -> bestKey(s, l) } }
                    .mapNotNull { key ->
                        node
                            .get(key)
                            ?.takeIf { it.isIntegralNumber && it.canConvertToLong() }
                            ?.asLong()
                            ?.takeIf { it > 0 }
                            ?.let { key to it }
                    }.toMap()
            } ?: emptyMap()
        return Values(size ?: SudokuSize.DEFAULT, level ?: Level.DEFAULT, best)
    }

    private fun write(values: Values) {
        val written =
            store.updateShared { root ->
                val node = root.get(FIELD) as? ObjectNode ?: root.objectNode()
                node.put(SIZE, values.size.side)
                node.put(LEVEL, values.level.key)
                val best = node.get(BEST) as? ObjectNode ?: root.objectNode()
                values.best.forEach { (key, millis) -> best.put(key, millis) }
                node.set(BEST, best)
                root.set(FIELD, node)
            }
        if (!written && !warned) {
            warned = true
            System
                .getLogger(SudokuPreferences::class.java.name)
                .log(System.Logger.Level.WARNING, "Sudoku settings not saved: the shared data file cannot be written")
        }
    }

    private fun bestKey(
        size: SudokuSize,
        level: Level,
    ) = "${size.side}-${level.key}"

    private companion object {
        const val FIELD = "sudoku"
        const val SIZE = "size"
        const val LEVEL = "level"
        const val BEST = "best"

        val DEFAULT = Values(SudokuSize.DEFAULT, Level.DEFAULT, emptyMap())
    }
}
