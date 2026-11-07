package dev.sluide.games.orbitguard

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrbitGuardPreferencesTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var store: ProjectDataStore
    private val thread: ExecutorService = NamedThreads.single("sluide-test")
    private val tasks = AtomicInteger()
    private val counting = Executor { task -> tasks.incrementAndGet().also { thread.execute(task) } }
    private val mapper = JsonMapper()

    @BeforeEach
    fun setUp() {
        store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
    }

    @AfterEach
    fun tearDown() {
        thread.shutdownNow()
        thread.awaitTermination(10, TimeUnit.SECONDS)
    }

    private fun preferences(delay: Duration = Duration.ZERO) = OrbitGuardPreferences(store, delay, counting)

    private fun writesDone() {
        thread.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun seed(json: String) {
        val node = mapper.readTree(json)
        store.updateShared { root -> node.properties().forEach { (name, value) -> root.set(name, value) } }
    }

    private fun stored() = store.readShared()?.get("orbitGuard")

    @Test
    fun `Normal and no best score without saved values`() {
        val preferences = preferences()

        assertEquals(Difficulty.NORMAL, preferences.lastDifficulty)
        Difficulty.entries.forEach { assertEquals(0, preferences.best(it)) }
    }

    @Test
    fun `a saved difficulty and a recorded score apply at once and are read back`() {
        val preferences = preferences()

        preferences.saveDifficulty(Difficulty.HARD)
        assertTrue(preferences.recordScore(Difficulty.EASY, 4210))

        assertEquals(Difficulty.HARD, preferences.lastDifficulty)
        assertEquals(4210, preferences.best(Difficulty.EASY))
        writesDone()
        assertEquals("""{"difficulty":"hard","best":{"easy":4210}}""", stored().toString())
        val again = preferences()
        assertEquals(Difficulty.HARD, again.lastDifficulty)
        assertEquals(4210, again.best(Difficulty.EASY))
    }

    @Test
    fun `a score is stored only when greater than the best`() {
        val preferences = preferences()
        assertTrue(preferences.recordScore(Difficulty.NORMAL, 100))

        assertFalse(preferences.recordScore(Difficulty.NORMAL, 100))
        assertFalse(preferences.recordScore(Difficulty.NORMAL, 40))
        assertFalse(preferences.recordScore(Difficulty.NORMAL, 0))
        assertTrue(preferences.recordScore(Difficulty.NORMAL, 101))

        assertEquals(101, preferences.best(Difficulty.NORMAL))
        assertEquals(0, preferences.best(Difficulty.HARD))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"orbitGuard":{"difficulty":"insane"}}""",
            """{"orbitGuard":{"difficulty":2}}""",
            """{"orbitGuard":{"difficulty":null}}""",
            """{"orbitGuard":{}}""",
            """{"orbitGuard":"hard"}""",
        ],
    )
    fun `invalid difficulties read as Normal`(json: String) {
        seed(json)

        assertEquals(Difficulty.NORMAL, preferences().lastDifficulty)
    }

    @Test
    fun `a valid difficulty is read`() {
        seed("""{"orbitGuard":{"difficulty":"easy"}}""")

        assertEquals(Difficulty.EASY, preferences().lastDifficulty)
    }

    @Test
    fun `invalid best entries are ignored`() {
        seed("""{"orbitGuard":{"best":{"easy":-5,"normal":"many","hard":12.5,"extreme":50}}}""")
        val preferences = preferences()

        Difficulty.entries.forEach { assertEquals(0, preferences.best(it), "$it") }

        seed("""{"orbitGuard":{"best":{"easy":900}}}""")
        assertEquals(900, preferences().best(Difficulty.EASY))
    }

    @Test
    fun `reads once and keeps the value in memory`() {
        seed("""{"orbitGuard":{"difficulty":"easy"}}""")
        val preferences = preferences()
        assertEquals(Difficulty.EASY, preferences.lastDifficulty)

        seed("""{"orbitGuard":{"difficulty":"hard"}}""")

        assertEquals(Difficulty.EASY, preferences.lastDifficulty)
    }

    @Test
    fun `quitting writes the pending value at once`() {
        val preferences = preferences(Duration.ofMinutes(1))
        preferences.saveDifficulty(Difficulty.HARD)

        preferences.destroy()

        assertEquals("hard", stored()?.get("difficulty")?.asString())
    }

    @Test
    fun `a save after quitting writes at once`() {
        val preferences = preferences(Duration.ofMinutes(1))
        preferences.destroy()

        preferences.recordScore(Difficulty.NORMAL, 64)

        assertEquals(64, stored()?.get("best")?.get("normal")?.asInt())
        assertEquals(0, tasks.get())
    }

    @Test
    fun `rapid changes end in one write of the latest values`() {
        // Long enough that the changes always fall within it, even on a slow runner.
        val preferences = preferences(Duration.ofMillis(500))

        preferences.saveDifficulty(Difficulty.EASY)
        preferences.recordScore(Difficulty.EASY, 10)
        preferences.recordScore(Difficulty.EASY, 20)
        preferences.saveDifficulty(Difficulty.HARD)
        writesDone()

        assertEquals(1, tasks.get())
        assertEquals("""{"difficulty":"hard","best":{"easy":20}}""", stored().toString())
    }

    @Test
    fun `a change after a write writes again`() {
        val preferences = preferences()

        preferences.saveDifficulty(Difficulty.EASY)
        writesDone()
        preferences.saveDifficulty(Difficulty.HARD)
        writesDone()

        assertEquals(2, tasks.get())
        assertEquals("hard", stored()?.get("difficulty")?.asString())
    }

    @Test
    fun `a change that changes nothing schedules no write`() {
        val preferences = preferences()
        preferences.recordScore(Difficulty.NORMAL, 100)
        writesDone()
        val written = tasks.get()

        preferences.recordScore(Difficulty.NORMAL, 40)
        preferences.recordScore(Difficulty.NORMAL, 100)
        preferences.saveDifficulty(Difficulty.NORMAL)
        writesDone()

        assertEquals(written, tasks.get())
        assertEquals("""{"difficulty":"normal","best":{"normal":100}}""", stored().toString())
    }

    @Test
    fun `no write at all for first changes that change nothing`() {
        val preferences = preferences()

        preferences.saveDifficulty(Difficulty.NORMAL)
        preferences.recordScore(Difficulty.EASY, 0)
        writesDone()

        assertEquals(0, tasks.get())
        assertNull(store.readShared())
    }

    @Test
    fun `quitting without a change writes nothing`() {
        preferences().destroy()

        assertNull(store.readShared())
    }

    @Test
    fun `other fields stay as they are and a non-object field is replaced`() {
        seed("""{"media":{"volume":0.3},"orbitGuard":{"later":"kept","best":{"easy":5,"insane":77}}}""")

        preferences().recordScore(Difficulty.NORMAL, 40)
        writesDone()

        assertEquals("""{"volume":0.3}""", store.readShared()?.get("media").toString())
        assertEquals("kept", stored()?.get("later")?.asString())
        assertEquals(5, stored()?.get("best")?.get("easy")?.asInt())
        assertEquals(77, stored()?.get("best")?.get("insane")?.asInt())
        assertEquals(40, stored()?.get("best")?.get("normal")?.asInt())

        seed("""{"orbitGuard":"x"}""")
        preferences().saveDifficulty(Difficulty.EASY)
        writesDone()
        assertEquals("""{"difficulty":"easy","best":{}}""", stored().toString())
    }
}
