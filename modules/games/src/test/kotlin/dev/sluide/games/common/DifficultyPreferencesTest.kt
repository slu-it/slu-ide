package dev.sluide.games.common

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
import kotlin.test.assertTrue

class DifficultyPreferencesTest {
    private enum class Level(
        val id: String,
    ) {
        EASY("easy"),
        NORMAL("normal"),
        HARD("hard"),
    }

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

    private fun preferences(delay: Duration = Duration.ZERO) =
        DifficultyPreferences(
            store = store,
            field = "game",
            gameName = "Test game",
            entries = Level.entries,
            id = Level::id,
            parse = { name -> Level.entries.firstOrNull { it.id == name } },
            default = Level.NORMAL,
            writeDelay = delay,
            writer = counting,
        )

    private fun writesDone() {
        thread.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun seed(json: String) {
        val node = mapper.readTree(json)
        store.updateShared { root -> node.properties().forEach { (name, value) -> root.set(name, value) } }
    }

    private fun stored() = store.readShared()?.get("game")

    @Test
    fun `the default and no best score without saved values`() {
        val preferences = preferences()

        assertEquals(Level.NORMAL, preferences.lastDifficulty)
        Level.entries.forEach { assertEquals(0, preferences.best(it)) }
    }

    @Test
    fun `a saved difficulty and a recorded score apply at once and are read back`() {
        val preferences = preferences()

        preferences.saveDifficulty(Level.HARD)
        assertTrue(preferences.recordScore(Level.EASY, 4210))

        assertEquals(Level.HARD, preferences.lastDifficulty)
        assertEquals(4210, preferences.best(Level.EASY))
        writesDone()
        assertEquals("""{"difficulty":"hard","best":{"easy":4210}}""", stored().toString())
        val again = preferences()
        assertEquals(Level.HARD, again.lastDifficulty)
        assertEquals(4210, again.best(Level.EASY))
    }

    @Test
    fun `a score is stored only when greater than the best`() {
        val preferences = preferences()
        assertTrue(preferences.recordScore(Level.NORMAL, 100))

        assertFalse(preferences.recordScore(Level.NORMAL, 100))
        assertFalse(preferences.recordScore(Level.NORMAL, 40))
        assertFalse(preferences.recordScore(Level.NORMAL, 0))
        assertTrue(preferences.recordScore(Level.NORMAL, 101))

        assertEquals(101, preferences.best(Level.NORMAL))
        assertEquals(0, preferences.best(Level.HARD))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"game":{"difficulty":"insane"}}""",
            """{"game":{"difficulty":2}}""",
            """{"game":{"difficulty":null}}""",
            """{"game":{}}""",
            """{"game":"hard"}""",
        ],
    )
    fun `invalid difficulties read as the default`(json: String) {
        seed(json)

        assertEquals(Level.NORMAL, preferences().lastDifficulty)
    }

    @Test
    fun `a valid difficulty is read`() {
        seed("""{"game":{"difficulty":"easy"}}""")

        assertEquals(Level.EASY, preferences().lastDifficulty)
    }

    @Test
    fun `invalid best entries are ignored`() {
        seed("""{"game":{"best":{"easy":-5,"normal":"many","hard":12.5,"extreme":50}}}""")
        val preferences = preferences()

        Level.entries.forEach { assertEquals(0, preferences.best(it), "$it") }

        seed("""{"game":{"best":{"easy":900}}}""")
        assertEquals(900, preferences().best(Level.EASY))
    }

    @Test
    fun `quitting writes the pending value at once`() {
        val preferences = preferences(Duration.ofMinutes(1))
        preferences.saveDifficulty(Level.HARD)

        preferences.destroy()

        assertEquals("hard", stored()?.get("difficulty")?.asString())
    }

    @Test
    fun `a change that changes nothing schedules no write`() {
        val preferences = preferences()
        preferences.recordScore(Level.NORMAL, 100)
        writesDone()
        val written = tasks.get()

        preferences.recordScore(Level.NORMAL, 40)
        preferences.recordScore(Level.NORMAL, 100)
        preferences.saveDifficulty(Level.NORMAL)
        writesDone()

        assertEquals(written, tasks.get())
        assertEquals("""{"difficulty":"normal","best":{"normal":100}}""", stored().toString())
    }

    @Test
    fun `other fields stay as they are and a non-object field is replaced`() {
        seed("""{"media":{"volume":0.3},"game":{"later":"kept","best":{"easy":5,"insane":77}}}""")

        preferences().recordScore(Level.NORMAL, 40)
        writesDone()

        assertEquals("""{"volume":0.3}""", store.readShared()?.get("media").toString())
        assertEquals("kept", stored()?.get("later")?.asString())
        assertEquals(5, stored()?.get("best")?.get("easy")?.asInt())
        assertEquals(77, stored()?.get("best")?.get("insane")?.asInt())
        assertEquals(40, stored()?.get("best")?.get("normal")?.asInt())

        seed("""{"game":"x"}""")
        preferences().saveDifficulty(Level.EASY)
        writesDone()
        assertEquals("""{"difficulty":"easy","best":{}}""", stored().toString())
    }
}
