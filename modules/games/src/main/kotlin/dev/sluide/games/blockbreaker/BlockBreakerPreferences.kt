package dev.sluide.games.blockbreaker

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.games.common.DifficultyPreferences
import dev.sluide.projectdata.ProjectDataStore
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.Executor

/**
 * The last difficulty and the best scores of Block Breaker, user-wide: in the top-level field `blockBreaker` of
 * `shared.json` (`{"difficulty": "normal", "best": {"easy": 4210}}`, [Difficulty.id] as names). The behavior (read
 * once, delayed writes, flush on [destroy], invalid values as defaults) is that of [DifficultyPreferences].
 */
@Component
class BlockBreakerPreferences internal constructor(
    store: ProjectDataStore,
    writeDelay: Duration,
    writer: Executor,
) : DisposableBean {
    @Autowired
    constructor(
        store: ProjectDataStore,
    ) : this(store, Duration.ofMillis(300), NamedThreads.single("sluide-block-breaker-prefs"))

    private val delegate =
        DifficultyPreferences(
            store = store,
            field = "blockBreaker",
            gameName = "Block Breaker",
            entries = Difficulty.entries,
            id = Difficulty::id,
            parse = Difficulty::ofId,
            default = Difficulty.DEFAULT,
            writeDelay = writeDelay,
            writer = writer,
        )

    /** The difficulty of the last game; Normal unless saved otherwise. */
    internal val lastDifficulty: Difficulty get() = delegate.lastDifficulty

    internal fun saveDifficulty(difficulty: Difficulty) = delegate.saveDifficulty(difficulty)

    /** The best score of [difficulty]; 0 when there is none. */
    internal fun best(difficulty: Difficulty): Int = delegate.best(difficulty)

    /** Stores [score] as the best of [difficulty] when it is greater than the stored one; true when stored. */
    internal fun recordScore(
        difficulty: Difficulty,
        score: Int,
    ): Boolean = delegate.recordScore(difficulty, score)

    override fun destroy() = delegate.destroy()
}
