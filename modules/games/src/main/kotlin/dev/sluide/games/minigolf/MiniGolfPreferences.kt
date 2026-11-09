package dev.sluide.games.minigolf

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
 * The best total and the best score per hole of Mini Golf, user-wide: in the top-level field `miniGolf` of
 * `shared.json` (`{"bestTotal": 49, "bestHoles": [2, 2, 3, null]}`; lower is better). The behavior (read once, delayed
 * writes, flush on [destroy]) is that of [StoredField]. A value that is no positive int reads as missing; other fields
 * stay as they are.
 */
@Component
class MiniGolfPreferences internal constructor(
    store: ProjectDataStore,
    writeDelay: Duration,
    writer: Executor,
) : DisposableBean {
    @Autowired
    constructor(
        store: ProjectDataStore,
    ) : this(store, Duration.ofMillis(300), NamedThreads.single("sluide-mini-golf-prefs"))

    private data class Values(
        val bestTotal: Int?,
        val bestHoles: List<Int?>,
    )

    private val stored =
        StoredField(
            store = store,
            fieldName = "miniGolf",
            gameName = "Mini Golf",
            writeDelay = writeDelay,
            writer = writer,
            read = ::read,
            write = ::write,
        )

    /** The lowest total of a completed round; null when there is none. */
    internal val bestTotal: Int? get() = stored.value.bestTotal

    /** The lowest score of each of [holeCount] holes; null where a hole was never finished. */
    internal fun bestHoles(holeCount: Int): List<Int?> = List(holeCount) { stored.value.bestHoles.getOrNull(it) }

    /** Stores [score] as the best of hole [index] when it is lower than the stored one; true when stored. */
    internal fun recordHole(
        index: Int,
        score: Int,
    ): Boolean {
        if (index < 0 || score <= 0) return false
        var done = false
        stored.change {
            val old = it.bestHoles.getOrNull(index)
            if (old == null || score < old) {
                done = true
                val holes = it.bestHoles.toMutableList()
                while (holes.size <= index) holes += null
                holes[index] = score
                it.copy(bestHoles = holes)
            } else {
                it
            }
        }
        return done
    }

    /** Stores [total] as the best total when it is lower than the stored one; true when stored. */
    internal fun recordTotal(total: Int): Boolean {
        if (total <= 0) return false
        var done = false
        stored.change {
            val old = it.bestTotal
            if (old == null || total < old) {
                done = true
                it.copy(bestTotal = total)
            } else {
                it
            }
        }
        return done
    }

    override fun destroy() = stored.destroy()

    private fun read(root: ObjectNode?): Values {
        if (root == null) return Values(null, emptyList())
        val array = root.get(BEST_HOLES)?.takeIf { it.isArray }
        val holes = if (array == null) emptyList() else List(array.size()) { positiveInt(array.get(it)) }
        return Values(positiveInt(root.get(BEST_TOTAL)), holes)
    }

    private fun positiveInt(node: JsonNode?): Int? =
        node?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()?.takeIf { it > 0 }

    private fun write(
        values: Values,
        node: ObjectNode,
    ) {
        values.bestTotal?.let { node.put(BEST_TOTAL, it) }
        if (values.bestHoles.isEmpty()) return
        val array = node.arrayNode()
        values.bestHoles.forEach { if (it == null) array.addNull() else array.add(it) }
        node.set(BEST_HOLES, array)
    }

    private companion object {
        const val BEST_TOTAL = "bestTotal"
        const val BEST_HOLES = "bestHoles"
    }
}
