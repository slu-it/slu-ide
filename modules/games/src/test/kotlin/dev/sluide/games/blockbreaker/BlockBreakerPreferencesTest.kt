package dev.sluide.games.blockbreaker

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals

/** The behavior is tested in `DifficultyPreferencesTest`; this is the field name and the own difficulty ids. */
class BlockBreakerPreferencesTest {
    @TempDir
    lateinit var dir: Path

    private val store by lazy { ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()) }

    private fun preferences() = BlockBreakerPreferences(store, Duration.ZERO, Executor { })

    @Test
    fun `difficulty and best scores round trip in the blockBreaker field`() {
        val preferences = preferences()
        preferences.saveDifficulty(Difficulty.HARD)
        preferences.recordScore(Difficulty.EASY, 4210)
        // The writer drops its tasks: the file is written only by destroy.
        preferences.destroy()

        assertEquals(
            """{"difficulty":"hard","best":{"easy":4210}}""",
            store.readShared()?.get("blockBreaker").toString(),
        )
        val again = preferences()
        assertEquals(Difficulty.HARD, again.lastDifficulty)
        assertEquals(4210, again.best(Difficulty.EASY))
        assertEquals(0, again.best(Difficulty.NORMAL))
    }

    @Test
    fun `Normal without saved values`() {
        assertEquals(Difficulty.NORMAL, preferences().lastDifficulty)
    }
}
