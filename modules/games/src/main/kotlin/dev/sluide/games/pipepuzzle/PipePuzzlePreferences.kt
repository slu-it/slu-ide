package dev.sluide.games.pipepuzzle

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.games.common.StoredField
import dev.sluide.projectdata.ProjectDataStore
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.Executor

/**
 * The last mode, size and look of Pipe Puzzle and the best times per mode and size, user-wide: in the top-level field
 * `pipePuzzle` of `shared.json` (`{"mode": "network", "size": 7, "look": "water", "best": {"network-7": 83000}}`, times
 * in milliseconds). The behavior (read once, delayed writes, flush on [destroy]) is that of [StoredField]. Invalid or
 * missing values read as the defaults (network, 7×7, water) and no best time; other fields stay as they are.
 */
@Component
class PipePuzzlePreferences internal constructor(
    store: ProjectDataStore,
    writeDelay: Duration,
    writer: Executor,
) : DisposableBean {
    @Autowired
    constructor(
        store: ProjectDataStore,
    ) : this(store, Duration.ofMillis(300), NamedThreads.single("sluide-pipe-puzzle-prefs"))

    private data class Values(
        val mode: Mode,
        val size: BoardSize,
        val look: Look,
        val best: Map<String, Long>,
    )

    private val stored =
        StoredField(
            store = store,
            fieldName = "pipePuzzle",
            gameName = "Pipe Puzzle",
            writeDelay = writeDelay,
            writer = writer,
            read = ::read,
            write = ::write,
        )

    /** The mode of the last game; network by default. */
    internal var lastMode: Mode
        get() = stored.value.mode
        set(value) = stored.change { it.copy(mode = value) }

    /** The size of the last game; 7×7 by default. */
    internal var lastSize: BoardSize
        get() = stored.value.size
        set(value) = stored.change { it.copy(size = value) }

    /** The look of the board; water by default. */
    internal var lastLook: Look
        get() = stored.value.look
        set(value) = stored.change { it.copy(look = value) }

    /** The best time in milliseconds for [mode] and [size]; null when there is none. */
    internal fun best(
        mode: Mode,
        size: BoardSize,
    ): Long? = stored.value.best[key(mode, size)]

    /** Stores [millis] as the best time when it is lower than the stored one; true when stored. */
    internal fun recordBest(
        mode: Mode,
        size: BoardSize,
        millis: Long,
    ): Boolean {
        if (millis <= 0) return false
        var done = false
        stored.change {
            val old = it.best[key(mode, size)]
            if (old == null || millis < old) {
                done = true
                it.copy(best = it.best + (key(mode, size) to millis))
            } else {
                it
            }
        }
        return done
    }

    override fun destroy() = stored.destroy()

    private fun key(
        mode: Mode,
        size: BoardSize,
    ) = "${mode.name.lowercase()}-${size.side}"

    private fun read(root: ObjectNode?): Values {
        if (root == null) return Values(Mode.NETWORK, BoardSize.S7, Look.WATER, emptyMap())
        val modeName = text(root.get(MODE))
        val lookName = text(root.get(LOOK))
        val side = root.get(SIZE)?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()
        val valid = Mode.entries.flatMap { m -> BoardSize.entries.map { s -> key(m, s) } }.toSet()
        val best = mutableMapOf<String, Long>()
        (root.get(BEST) as? ObjectNode)?.properties()?.forEach { (name, value) ->
            val millis = positiveLong(value)
            if (name in valid && millis != null) best[name] = millis
        }
        return Values(
            Mode.entries.firstOrNull { it.name.lowercase() == modeName } ?: Mode.NETWORK,
            BoardSize.entries.firstOrNull { it.side == side } ?: BoardSize.S7,
            Look.entries.firstOrNull { it.name.lowercase() == lookName } ?: Look.WATER,
            best,
        )
    }

    private fun text(node: JsonNode?): String? = node?.takeIf { it.isString }?.asString()

    private fun positiveLong(node: JsonNode?): Long? =
        node?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()?.takeIf { it > 0 }

    private fun write(
        values: Values,
        node: ObjectNode,
    ) {
        node.put(MODE, values.mode.name.lowercase())
        node.put(SIZE, values.size.side)
        node.put(LOOK, values.look.name.lowercase())
        if (values.best.isEmpty()) return
        val best = node.objectNode()
        values.best.toSortedMap().forEach { (name, millis) -> best.put(name, millis) }
        node.set(BEST, best)
    }

    private companion object {
        const val MODE = "mode"
        const val SIZE = "size"
        const val LOOK = "look"
        const val BEST = "best"
    }
}
