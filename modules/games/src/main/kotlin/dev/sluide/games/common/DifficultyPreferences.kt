package dev.sluide.games.common

import dev.sluide.projectdata.ProjectDataStore
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.Executor

/**
 * The last difficulty and the best scores of one game, user-wide: in the top-level field [field] of `shared.json`,
 * `{"difficulty": "normal", "best": {"easy": 4210, "normal": 12340}}` ([id] of the difficulties as names). Reading and
 * writing (read once, delayed writes, flush on [destroy]) are those of [StoredField]. A missing or invalid value reads
 * as its default; other fields of [field] and other keys of `best` stay as they are. [gameName] is the name in the log
 * text when writing fails.
 *
 * Not a Spring bean: the game's preferences component delegates to it. [D] is the game's difficulty type, [entries]
 * all of its values, [id] the name of one in the file, [parse] its inverse (null when unknown) and [default] the value
 * for a missing or invalid one.
 */
internal class DifficultyPreferences<D : Any>(
    store: ProjectDataStore,
    field: String,
    gameName: String,
    private val entries: List<D>,
    private val id: (D) -> String,
    private val parse: (String?) -> D?,
    private val default: D,
    writeDelay: Duration,
    writer: Executor,
) {
    private data class Values<D>(
        val difficulty: D,
        val best: Map<D, Int>,
    )

    private val stored =
        StoredField(
            store = store,
            fieldName = field,
            gameName = gameName,
            writeDelay = writeDelay,
            writer = writer,
            read = ::read,
            write = ::write,
        )

    /** The difficulty of the last game; the default unless saved otherwise. */
    val lastDifficulty: D get() = stored.value.difficulty

    fun saveDifficulty(difficulty: D) {
        stored.change { it.copy(difficulty = difficulty) }
    }

    /** The best score of [difficulty]; 0 when there is none. */
    fun best(difficulty: D): Int = stored.value.best[difficulty] ?: 0

    /** Stores [score] as the best of [difficulty] when it is greater than the stored one; true when stored. */
    fun recordScore(
        difficulty: D,
        score: Int,
    ): Boolean {
        var done = false
        stored.change {
            if (score > (it.best[difficulty] ?: 0)) {
                done = true
                it.copy(best = it.best + (difficulty to score))
            } else {
                it
            }
        }
        return done
    }

    /** The app quits: the writer is a daemon thread, so pending values are written now; later changes write at once. */
    fun destroy() = stored.destroy()

    private fun read(root: ObjectNode?): Values<D> {
        if (root == null) return Values(default, emptyMap())
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

    private fun write(
        values: Values<D>,
        node: ObjectNode,
    ) {
        node.put(DIFFICULTY, id(values.difficulty))
        val best = node.get(BEST) as? ObjectNode ?: node.objectNode()
        values.best.forEach { (level, score) -> best.put(id(level), score) }
        node.set(BEST, best)
    }

    private companion object {
        const val DIFFICULTY = "difficulty"
        const val BEST = "best"
    }
}
