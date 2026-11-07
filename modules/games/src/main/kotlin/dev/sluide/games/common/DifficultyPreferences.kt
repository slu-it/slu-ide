package dev.sluide.games.common

import dev.sluide.projectdata.ProjectDataStore
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * The last difficulty and the best scores of one game, user-wide: in the top-level field [field] of `shared.json`,
 * `{"difficulty": "normal", "best": {"easy": 4210, "normal": 12340}}` ([id] of the difficulties as names). Read once on
 * first use, then kept in memory; changes apply at once and are written off the FX thread, saves within [writeDelay]
 * of each other ending in one write. [destroy] writes a pending change at once, and so does every later change. A
 * missing or invalid value reads as its default; other fields of [field] and other keys of `best` stay as they are, a
 * [field] that is no JSON object is replaced. [gameName] is the name in the log text when writing fails.
 *
 * Not a Spring bean: the game's preferences component delegates to it. [D] is the game's difficulty type, [entries]
 * all of its values, [id] the name of one in the file, [parse] its inverse (null when unknown) and [default] the value
 * for a missing or invalid one.
 */
internal class DifficultyPreferences<D : Any>(
    private val store: ProjectDataStore,
    private val field: String,
    private val gameName: String,
    private val entries: List<D>,
    private val id: (D) -> String,
    private val parse: (String?) -> D?,
    private val default: D,
    private val writeDelay: Duration,
    private val writer: Executor,
) {
    private data class Values<D>(
        val difficulty: D,
        val best: Map<D, Int>,
    )

    @Volatile
    private var current: Values<D>? = null
    private val pending = AtomicReference<Values<D>?>()
    private val changeLock = Any()

    /** Held while taking and writing the pending values, so that [destroy] waits for a write in progress. */
    private val writeLock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var warned = false

    /** The difficulty of the last game; the default unless saved otherwise. */
    val lastDifficulty: D get() = values().difficulty

    fun saveDifficulty(difficulty: D) {
        change { it.copy(difficulty = difficulty) }
    }

    /** The best score of [difficulty]; 0 when there is none. */
    fun best(difficulty: D): Int = values().best[difficulty] ?: 0

    /** Stores [score] as the best of [difficulty] when it is greater than the stored one; true when stored. */
    fun recordScore(
        difficulty: D,
        score: Int,
    ): Boolean {
        var stored = false
        change {
            if (score > (it.best[difficulty] ?: 0)) {
                stored = true
                it.copy(best = it.best + (difficulty to score))
            } else {
                it
            }
        }
        return stored
    }

    /** The app quits: the writer is a daemon thread, so pending values are written now; later changes write at once. */
    fun destroy() {
        closed = true
        flush()
    }

    private fun change(transform: (Values<D>) -> Values<D>) {
        var before: Values<D>? = null
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

    private fun values(): Values<D> = current ?: read().also { current = it }

    private fun read(): Values<D> {
        val root = store.readShared()?.get(field) as? ObjectNode ?: return Values(default, emptyMap())
        val name = root.get(DIFFICULTY)?.takeIf { it.isString }?.asString()
        val difficulty = parse(name)
        val best =
            (root.get(BEST) as? ObjectNode)?.let { node ->
                entries
                    .mapNotNull { level ->
                        node
                            .get(id(level))
                            ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
                            ?.asInt()
                            ?.takeIf { it > 0 }
                            ?.let { level to it }
                    }.toMap()
            } ?: emptyMap()
        return Values(difficulty ?: default, best)
    }

    private fun write(values: Values<D>) {
        val written =
            store.updateShared { root ->
                val node = root.get(field) as? ObjectNode ?: root.objectNode()
                node.put(DIFFICULTY, id(values.difficulty))
                val best = node.get(BEST) as? ObjectNode ?: root.objectNode()
                values.best.forEach { (level, score) -> best.put(id(level), score) }
                node.set(BEST, best)
                root.set(field, node)
            }
        if (!written && !warned) {
            warned = true
            System
                .getLogger(DifficultyPreferences::class.java.name)
                .log(
                    System.Logger.Level.WARNING,
                    "$gameName settings not saved: the shared data file cannot be written",
                )
        }
    }

    private companion object {
        const val DIFFICULTY = "difficulty"
        const val BEST = "best"
    }
}
