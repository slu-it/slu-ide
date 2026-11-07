package dev.sluide.games.solitaire

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
 * The last game and the high scores of Solitaire, user-wide: in the top-level field `solitaire` of `shared.json`,
 * `{"game": "spider", "draw": 3, "suits": 2, "high": {"klondike-1": 4210, "spider-2": 812}}` (`game` is `klondike`,
 * `freecell` or `spider`, `draw` 1 or 3, `suits` 1, 2 or 4, `high` maps a [Mode.key] to its high score, which may be
 * negative). Read once on first use, then kept in memory; changes apply at once and are written off the FX thread,
 * saves within [writeDelay] of each other ending in one write. [destroy] writes a pending change at once, and so does
 * every later change. A missing or invalid value reads as its default; other fields of `solitaire` and other keys of
 * `high` stay as they are, a `solitaire` that is no JSON object is replaced.
 */
@Component
class SolitairePreferences internal constructor(
    private val store: ProjectDataStore,
    private val writeDelay: Duration,
    private val writer: Executor,
) : DisposableBean {
    @Autowired
    constructor(
        store: ProjectDataStore,
    ) : this(store, Duration.ofMillis(300), NamedThreads.single("sluide-solitaire-prefs"))

    private data class Values(
        val game: Variant,
        val draw: Int,
        val suits: Int,
        val high: Map<String, Int>,
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

    /** The mode of the last game; Klondike drawing one card unless saved otherwise. */
    internal val lastMode: Mode get() = values().let { modeOf(it, it.game) }

    /** The mode to use when switching to [variant]: its stored draw or suits option. */
    internal fun modeFor(variant: Variant): Mode = modeOf(values(), variant)

    /** Stores the game of [mode] and its draw or suits option; the option of the other variant stays as it is. */
    internal fun saveMode(mode: Mode) {
        change {
            when (mode) {
                Mode.KLONDIKE_DRAW_1 -> it.copy(game = mode.variant, draw = 1)
                Mode.KLONDIKE_DRAW_3 -> it.copy(game = mode.variant, draw = 3)
                Mode.FREECELL -> it.copy(game = mode.variant)
                Mode.SPIDER_1 -> it.copy(game = mode.variant, suits = 1)
                Mode.SPIDER_2 -> it.copy(game = mode.variant, suits = 2)
                Mode.SPIDER_4 -> it.copy(game = mode.variant, suits = 4)
            }
        }
    }

    /** The high score of [mode]; null when there is none. */
    internal fun highScore(mode: Mode): Int? = values().high[mode.key]

    /** Stores [score] as the high score of [mode] when there is none or it is higher; true when stored. */
    internal fun recordScore(
        mode: Mode,
        score: Int,
    ): Boolean {
        var stored = false
        change {
            val old = it.high[mode.key]
            if (old == null || score > old) {
                stored = true
                it.copy(high = it.high + (mode.key to score))
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

    private fun modeOf(
        values: Values,
        variant: Variant,
    ): Mode =
        when (variant) {
            Variant.KLONDIKE -> {
                if (values.draw == 3) Mode.KLONDIKE_DRAW_3 else Mode.KLONDIKE_DRAW_1
            }

            Variant.FREECELL -> {
                Mode.FREECELL
            }

            Variant.SPIDER -> {
                when (values.suits) {
                    2 -> Mode.SPIDER_2
                    4 -> Mode.SPIDER_4
                    else -> Mode.SPIDER_1
                }
            }
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
        val game =
            root
                .get(GAME)
                ?.takeIf { it.isString }
                ?.asString()
                ?.let { key -> Variant.entries.firstOrNull { it.name.lowercase() == key } }
        val draw = root.int(DRAW)?.takeIf { it == 1 || it == 3 }
        val suits = root.int(SUITS)?.takeIf { it == 1 || it == 2 || it == 4 }
        val high =
            (root.get(HIGH) as? ObjectNode)?.let { node ->
                Mode.entries
                    .mapNotNull { mode -> node.int(mode.key)?.let { mode.key to it } }
                    .toMap()
            } ?: emptyMap()
        return Values(game ?: DEFAULT.game, draw ?: DEFAULT.draw, suits ?: DEFAULT.suits, high)
    }

    private fun ObjectNode.int(name: String): Int? =
        get(name)
            ?.takeIf {
                it.isIntegralNumber && it.canConvertToInt()
            }?.asInt()

    private fun write(values: Values) {
        val written =
            store.updateShared { root ->
                val node = root.get(FIELD) as? ObjectNode ?: root.objectNode()
                node.put(GAME, values.game.name.lowercase())
                node.put(DRAW, values.draw)
                node.put(SUITS, values.suits)
                val high = node.get(HIGH) as? ObjectNode ?: root.objectNode()
                values.high.forEach { (key, score) -> high.put(key, score) }
                node.set(HIGH, high)
                root.set(FIELD, node)
            }
        if (!written && !warned) {
            warned = true
            System
                .getLogger(SolitairePreferences::class.java.name)
                .log(
                    System.Logger.Level.WARNING,
                    "Solitaire settings not saved: the shared data file cannot be written",
                )
        }
    }

    private companion object {
        const val FIELD = "solitaire"
        const val GAME = "game"
        const val DRAW = "draw"
        const val SUITS = "suits"
        const val HIGH = "high"

        val DEFAULT = Values(Mode.DEFAULT.variant, 1, 1, emptyMap())
    }
}
