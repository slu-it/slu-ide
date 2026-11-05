package dev.sluide.games.minesweeper

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.projectdata.ProjectDataStore
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * The last board size and the best times of Minesweeper, user-wide: in the top-level field `minesweeper` of
 * `shared.json` (ADR 0101), `{"size": "beginner"|"intermediate"|"expert"|"custom", "custom": {"width": 20,
 * "height": 12, "mines": 40}, "best": {"beginner": 12345}}` (best times in ms). Read once on first use, then kept in
 * memory; changes apply at once and are written off the FX thread, saves within [writeDelay] of each other ending in
 * one write. [destroy] writes a pending change at once, and so does every later change.
 *
 * A missing or invalid value reads as its default; stored sizes are clamped to the limits. Other fields of
 * `minesweeper` stay as they are; a `minesweeper` that is no JSON object is replaced.
 */
@Component
class MinesweeperPreferences internal constructor(
    private val store: ProjectDataStore,
    private val writeDelay: Duration,
    private val writer: Executor,
) : DisposableBean {
    @Autowired
    constructor(
        store: ProjectDataStore,
    ) : this(store, Duration.ofMillis(300), NamedThreads.single("sluide-minesweeper-prefs"))

    private data class Values(
        val size: SizeChoice,
        val custom: BoardSize,
        val best: Map<Preset, Long>,
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

    /** The size of the last game; the beginner preset unless saved otherwise. */
    val lastSize: SizeChoice get() = values().size

    /** The values the Custom dialog starts with. */
    val lastCustom: BoardSize get() = values().custom

    fun bestTime(preset: Preset): Long? = values().best[preset]

    /** Remembers [choice]; a custom one also becomes [lastCustom]. */
    fun saveSize(choice: SizeChoice) {
        change { it.copy(size = choice, custom = (choice as? SizeChoice.Custom)?.size ?: it.custom) }
    }

    /** Stores [millis] (above 0) as the best time of [preset] if there is none yet or it is faster; true when stored. */
    fun recordWin(
        preset: Preset,
        millis: Long,
    ): Boolean {
        if (millis <= 0) return false
        var stored = false
        change {
            val best = it.best[preset]
            if (best == null || millis < best) {
                stored = true
                it.copy(best = it.best + (preset to millis))
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
        val values =
            synchronized(changeLock) {
                transform(values()).also { current = it }
            }
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
        val storedCustom = readCustom(root.get(CUSTOM))
        val name = root.get(SIZE)?.takeIf { it.isString }?.asString()
        val size =
            if (name == "custom") {
                storedCustom?.let { SizeChoice.Custom(it) }
            } else {
                Preset.entries.firstOrNull { it.key == name }?.let { SizeChoice.OfPreset(it) }
            } ?: DEFAULT.size
        val best =
            (root.get(BEST) as? ObjectNode)?.let { node ->
                Preset.entries
                    .mapNotNull { preset ->
                        node
                            .get(preset.key)
                            ?.takeIf { it.isIntegralNumber && it.canConvertToLong() }
                            ?.asLong()
                            ?.takeIf { it > 0 }
                            ?.let { preset to it }
                    }.toMap()
            } ?: emptyMap()
        return Values(size, storedCustom ?: DEFAULT.custom, best)
    }

    private fun readCustom(node: JsonNode?): BoardSize? {
        if (node !is ObjectNode) return null

        fun int(name: String) = node.get(name)?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()
        val width = int(WIDTH) ?: return null
        val height = int(HEIGHT) ?: return null
        val mines = int(MINES) ?: return null
        return BoardSize.clamped(width, height, mines)
    }

    private fun write(values: Values) {
        val written =
            store.updateShared { root ->
                val node = root.get(FIELD) as? ObjectNode ?: root.objectNode()
                node.put(
                    SIZE,
                    when (val size = values.size) {
                        is SizeChoice.OfPreset -> size.preset.key
                        is SizeChoice.Custom -> "custom"
                    },
                )
                node.set(
                    CUSTOM,
                    root
                        .objectNode()
                        .put(WIDTH, values.custom.width)
                        .put(HEIGHT, values.custom.height)
                        .put(MINES, values.custom.mines),
                )
                val best = node.get(BEST) as? ObjectNode ?: root.objectNode()
                values.best.forEach { (preset, millis) -> best.put(preset.key, millis) }
                node.set(BEST, best)
                root.set(FIELD, node)
            }
        if (!written && !warned) {
            warned = true
            System
                .getLogger(MinesweeperPreferences::class.java.name)
                .log(
                    System.Logger.Level.WARNING,
                    "Minesweeper settings not saved: the shared data file cannot be written",
                )
        }
    }

    private val Preset.key: String get() = name.lowercase()

    private companion object {
        const val FIELD = "minesweeper"
        const val SIZE = "size"
        const val CUSTOM = "custom"
        const val BEST = "best"
        const val WIDTH = "width"
        const val HEIGHT = "height"
        const val MINES = "mines"

        val DEFAULT = Values(SizeChoice.OfPreset(Preset.BEGINNER), BoardSize(20, 12, 40), emptyMap())
    }
}
