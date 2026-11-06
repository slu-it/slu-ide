package dev.sluide.games.sudoku

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

class SudokuPreferencesTest {
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

    private fun preferences(delay: Duration = Duration.ZERO) = SudokuPreferences(store, delay, counting)

    private fun writesDone() {
        thread.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun seed(json: String) {
        val node = mapper.readTree(json)
        store.updateShared { root -> node.properties().forEach { (name, value) -> root.set(name, value) } }
    }

    private fun stored() = store.readShared()?.get("sudoku")

    @Test
    fun `9x9 medium and no best time without saved values`() {
        val preferences = preferences()

        assertEquals(SudokuSize.NINE, preferences.lastSize)
        assertEquals(Level.MEDIUM, preferences.lastLevel)
        assertNull(preferences.best(SudokuSize.NINE, Level.MEDIUM))
    }

    @Test
    fun `a saved choice and a recorded time apply at once and are read back`() {
        val preferences = preferences()

        preferences.save(SudokuSize.FOUR, Level.HARD)
        assertTrue(preferences.recordTime(SudokuSize.SIX, Level.EASY, 21000))

        assertEquals(SudokuSize.FOUR, preferences.lastSize)
        assertEquals(Level.HARD, preferences.lastLevel)
        assertEquals(21000L, preferences.best(SudokuSize.SIX, Level.EASY))
        writesDone()
        assertEquals("""{"size":4,"level":"hard","best":{"6-easy":21000}}""", stored().toString())
        val again = preferences()
        assertEquals(SudokuSize.FOUR, again.lastSize)
        assertEquals(Level.HARD, again.lastLevel)
        assertEquals(21000L, again.best(SudokuSize.SIX, Level.EASY))
    }

    @Test
    fun `a time is stored only when lower than the best`() {
        val preferences = preferences()
        assertTrue(preferences.recordTime(SudokuSize.NINE, Level.HARD, 754000))

        assertFalse(preferences.recordTime(SudokuSize.NINE, Level.HARD, 754000))
        assertFalse(preferences.recordTime(SudokuSize.NINE, Level.HARD, 900000))
        assertFalse(preferences.recordTime(SudokuSize.NINE, Level.HARD, 0))
        assertTrue(preferences.recordTime(SudokuSize.NINE, Level.HARD, 700000))

        assertEquals(700000L, preferences.best(SudokuSize.NINE, Level.HARD))
        assertNull(preferences.best(SudokuSize.NINE, Level.EASY))
        assertNull(preferences.best(SudokuSize.FOUR, Level.HARD))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"sudoku":{"size":7}}""",
            """{"sudoku":{"size":"9","level":3}}""",
            """{"sudoku":{"size":4.5,"level":"nightmare"}}""",
            """{"sudoku":{"size":null,"level":null}}""",
            """{"sudoku":{}}""",
            """{"sudoku":"x"}""",
        ],
    )
    fun `invalid values read as defaults`(json: String) {
        seed(json)
        val preferences = preferences()

        assertEquals(SudokuSize.NINE, preferences.lastSize)
        assertEquals(Level.MEDIUM, preferences.lastLevel)
    }

    @Test
    fun `valid values are read`() {
        seed("""{"sudoku":{"size":6,"level":"expert"}}""")
        val preferences = preferences()

        assertEquals(SudokuSize.SIX, preferences.lastSize)
        assertEquals(Level.EXPERT, preferences.lastLevel)
    }

    @Test
    fun `invalid best entries are ignored`() {
        seed("""{"sudoku":{"best":{"9-hard":754000,"4-easy":"fast","6-easy":-5,"9-easy":1.5,"5-easy":10,"9-x":10}}}""")
        val preferences = preferences()

        assertEquals(754000L, preferences.best(SudokuSize.NINE, Level.HARD))
        assertNull(preferences.best(SudokuSize.FOUR, Level.EASY))
        assertNull(preferences.best(SudokuSize.SIX, Level.EASY))
        assertNull(preferences.best(SudokuSize.NINE, Level.EASY))
    }

    @Test
    fun `reads once and keeps the value in memory`() {
        seed("""{"sudoku":{"size":4}}""")
        val preferences = preferences()
        assertEquals(SudokuSize.FOUR, preferences.lastSize)

        seed("""{"sudoku":{"size":6}}""")

        assertEquals(SudokuSize.FOUR, preferences.lastSize)
    }

    @Test
    fun `quitting writes the pending value at once`() {
        val preferences = preferences(Duration.ofMinutes(1))
        preferences.save(SudokuSize.SIX, Level.EASY)

        preferences.destroy()

        assertEquals(6, stored()?.get("size")?.asInt())
    }

    @Test
    fun `a save after quitting writes at once`() {
        val preferences = preferences(Duration.ofMinutes(1))
        preferences.destroy()

        preferences.recordTime(SudokuSize.FOUR, Level.EASY, 64000)

        assertEquals(64000L, stored()?.get("best")?.get("4-easy")?.asLong())
        assertEquals(0, tasks.get())
    }

    @Test
    fun `rapid changes end in one write of the latest values`() {
        // Long enough that the changes always fall within it, even on a slow runner.
        val preferences = preferences(Duration.ofMillis(500))

        preferences.save(SudokuSize.FOUR, Level.EASY)
        preferences.recordTime(SudokuSize.FOUR, Level.EASY, 100)
        preferences.recordTime(SudokuSize.FOUR, Level.EASY, 50)
        preferences.save(SudokuSize.SIX, Level.HARD)
        writesDone()

        assertEquals(1, tasks.get())
        assertEquals("""{"size":6,"level":"hard","best":{"4-easy":50}}""", stored().toString())
    }

    @Test
    fun `a change that changes nothing schedules no write`() {
        val preferences = preferences()

        preferences.save(SudokuSize.NINE, Level.MEDIUM)
        preferences.recordTime(SudokuSize.NINE, Level.MEDIUM, 0)
        writesDone()

        assertEquals(0, tasks.get())
        assertNull(store.readShared())
    }

    @Test
    fun `other fields stay as they are and a non-object field is replaced`() {
        seed("""{"media":{"volume":0.3},"sudoku":{"later":"kept","best":{"4-easy":5,"9-x":77}}}""")

        preferences().recordTime(SudokuSize.FOUR, Level.HARD, 40)
        writesDone()

        assertEquals("""{"volume":0.3}""", store.readShared()?.get("media").toString())
        assertEquals("kept", stored()?.get("later")?.asString())
        assertEquals(5, stored()?.get("best")?.get("4-easy")?.asInt())
        assertEquals(77, stored()?.get("best")?.get("9-x")?.asInt())
        assertEquals(40, stored()?.get("best")?.get("4-hard")?.asInt())

        seed("""{"sudoku":"x"}""")
        preferences().save(SudokuSize.FOUR, Level.EASY)
        writesDone()
        assertEquals("""{"size":4,"level":"easy","best":{}}""", stored().toString())
    }
}
