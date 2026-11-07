package dev.sluide.games.orbitguard

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
 * The last difficulty and the best scores of Orbit Guard, user-wide: in the top-level field `orbitGuard` of
 * `shared.json`, `{"difficulty": "normal", "best": {"easy": 4210, "normal": 12340}}` ([Difficulty.id] as names). Read
 * once on first use, then kept in memory; changes apply at once and are written off the FX thread, saves within
 * [writeDelay] of each other ending in one write. [destroy] writes a pending change at once, and so does every later
 * change. A missing or invalid value reads as its default; other fields of `orbitGuard` and other keys of `best` stay
 * as they are, an `orbitGuard` that is no JSON object is replaced.
 */
@Component
class OrbitGuardPreferences internal constructor(
    private val store: ProjectDataStore,
    private val writeDelay: Duration,
    private val writer: Executor,
) : DisposableBean {
    @Autowired
    constructor(
        store: ProjectDataStore,
    ) : this(store, Duration.ofMillis(300), NamedThreads.single("sluide-orbit-guard-prefs"))

    private data class Values(
        val difficulty: Difficulty,
        val best: Map<Difficulty, Int>,
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

    /** The difficulty of the last game; Normal unless saved otherwise. */
    internal val lastDifficulty: Difficulty get() = values().difficulty

    internal fun saveDifficulty(difficulty: Difficulty) {
        change { it.copy(difficulty = difficulty) }
    }

    /** The best score of [difficulty]; 0 when there is none. */
    internal fun best(difficulty: Difficulty): Int = values().best[difficulty] ?: 0

    /** Stores [score] as the best of [difficulty] when it is greater than the stored one; true when stored. */
    internal fun recordScore(
        difficulty: Difficulty,
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
        val difficulty = Difficulty.ofId(root.get(DIFFICULTY)?.takeIf { it.isString }?.asString())
        val best =
            (root.get(BEST) as? ObjectNode)?.let { node ->
                Difficulty.entries
                    .mapNotNull { level ->
                        node
                            .get(level.id)
                            ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
                            ?.asInt()
                            ?.takeIf { it > 0 }
                            ?.let { level to it }
                    }.toMap()
            } ?: emptyMap()
        return Values(difficulty ?: Difficulty.DEFAULT, best)
    }

    private fun write(values: Values) {
        val written =
            store.updateShared { root ->
                val node = root.get(FIELD) as? ObjectNode ?: root.objectNode()
                node.put(DIFFICULTY, values.difficulty.id)
                val best = node.get(BEST) as? ObjectNode ?: root.objectNode()
                values.best.forEach { (level, score) -> best.put(level.id, score) }
                node.set(BEST, best)
                root.set(FIELD, node)
            }
        if (!written && !warned) {
            warned = true
            System
                .getLogger(OrbitGuardPreferences::class.java.name)
                .log(
                    System.Logger.Level.WARNING,
                    "Orbit Guard settings not saved: the shared data file cannot be written",
                )
        }
    }

    private companion object {
        const val FIELD = "orbitGuard"
        const val DIFFICULTY = "difficulty"
        const val BEST = "best"

        val DEFAULT = Values(Difficulty.DEFAULT, emptyMap())
    }
}
