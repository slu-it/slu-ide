package dev.sluide.games.doubles

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

class DoublesPreferencesTest {
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

    private fun preferences(delay: Duration = Duration.ZERO) = DoublesPreferences(store, delay, counting)

    private fun writesDone() {
        thread.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun seed(json: String) {
        val node = mapper.readTree(json)
        store.updateShared { root -> node.properties().forEach { (name, value) -> root.set(name, value) } }
    }

    private fun stored() = store.readShared()?.get("doubles")

    @Test
    fun `4x4 and no best score without saved values`() {
        val preferences = preferences()

        assertEquals(BoardSize.FOUR, preferences.lastSize)
        BoardSize.entries.forEach { assertEquals(0, preferences.best(it)) }
    }

    @Test
    fun `a saved size and a recorded score apply at once and are read back`() {
        val preferences = preferences()

        preferences.saveSize(BoardSize.SIX)
        assertTrue(preferences.recordScore(BoardSize.THREE, 812))

        assertEquals(BoardSize.SIX, preferences.lastSize)
        assertEquals(812, preferences.best(BoardSize.THREE))
        writesDone()
        assertEquals("""{"size":6,"best":{"3":812}}""", stored().toString())
        val again = preferences()
        assertEquals(BoardSize.SIX, again.lastSize)
        assertEquals(812, again.best(BoardSize.THREE))
    }

    @Test
    fun `a score is stored only when greater than the best`() {
        val preferences = preferences()
        assertTrue(preferences.recordScore(BoardSize.FOUR, 100))

        assertFalse(preferences.recordScore(BoardSize.FOUR, 100))
        assertFalse(preferences.recordScore(BoardSize.FOUR, 40))
        assertFalse(preferences.recordScore(BoardSize.FOUR, 0))
        assertTrue(preferences.recordScore(BoardSize.FOUR, 101))

        assertEquals(101, preferences.best(BoardSize.FOUR))
        assertEquals(0, preferences.best(BoardSize.FIVE))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"doubles":{"size":7}}""",
            """{"doubles":{"size":"4"}}""",
            """{"doubles":{"size":4.5}}""",
            """{"doubles":{"size":null}}""",
            """{"doubles":{}}""",
            """{"doubles":"4"}""",
        ],
    )
    fun `invalid sizes read as 4x4`(json: String) {
        seed(json)

        assertEquals(BoardSize.FOUR, preferences().lastSize)
    }

    @Test
    fun `a valid size is read`() {
        seed("""{"doubles":{"size":5}}""")

        assertEquals(BoardSize.FIVE, preferences().lastSize)
    }

    @Test
    fun `invalid best entries are ignored`() {
        seed("""{"doubles":{"best":{"3":-5,"4":"many","5":12.5,"6":900,"7":50}}}""")
        val preferences = preferences()

        assertEquals(0, preferences.best(BoardSize.THREE))
        assertEquals(0, preferences.best(BoardSize.FOUR))
        assertEquals(0, preferences.best(BoardSize.FIVE))
        assertEquals(900, preferences.best(BoardSize.SIX))
    }

    @Test
    fun `reads once and keeps the value in memory`() {
        seed("""{"doubles":{"size":3}}""")
        val preferences = preferences()
        assertEquals(BoardSize.THREE, preferences.lastSize)

        seed("""{"doubles":{"size":5}}""")

        assertEquals(BoardSize.THREE, preferences.lastSize)
    }

    @Test
    fun `quitting writes the pending value at once`() {
        val preferences = preferences(Duration.ofMinutes(1))
        preferences.saveSize(BoardSize.FIVE)

        preferences.destroy()

        assertEquals(5, stored()?.get("size")?.asInt())
    }

    @Test
    fun `a save after quitting writes at once`() {
        val preferences = preferences(Duration.ofMinutes(1))
        preferences.destroy()

        preferences.recordScore(BoardSize.FOUR, 64)

        assertEquals(64, stored()?.get("best")?.get("4")?.asInt())
        assertEquals(0, tasks.get())
    }

    @Test
    fun `rapid changes end in one write of the latest values`() {
        // Long enough that the changes always fall within it, even on a slow runner.
        val preferences = preferences(Duration.ofMillis(500))

        preferences.saveSize(BoardSize.THREE)
        preferences.recordScore(BoardSize.THREE, 10)
        preferences.recordScore(BoardSize.THREE, 20)
        preferences.saveSize(BoardSize.SIX)
        writesDone()

        assertEquals(1, tasks.get())
        assertEquals("""{"size":6,"best":{"3":20}}""", stored().toString())
    }

    @Test
    fun `a change after a write writes again`() {
        val preferences = preferences()

        preferences.saveSize(BoardSize.THREE)
        writesDone()
        preferences.saveSize(BoardSize.FIVE)
        writesDone()

        assertEquals(2, tasks.get())
        assertEquals(5, stored()?.get("size")?.asInt())
    }

    @Test
    fun `a change that changes nothing schedules no write`() {
        val preferences = preferences()
        preferences.recordScore(BoardSize.FOUR, 100)
        writesDone()
        val written = tasks.get()

        preferences.recordScore(BoardSize.FOUR, 40)
        preferences.recordScore(BoardSize.FOUR, 100)
        preferences.saveSize(BoardSize.FOUR)
        writesDone()

        assertEquals(written, tasks.get())
        assertEquals("""{"size":4,"best":{"4":100}}""", stored().toString())
    }

    @Test
    fun `no write at all for first changes that change nothing`() {
        val preferences = preferences()

        preferences.saveSize(BoardSize.FOUR)
        preferences.recordScore(BoardSize.THREE, 0)
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
        seed("""{"media":{"volume":0.3},"doubles":{"later":"kept","best":{"3":5,"9":77}}}""")

        preferences().recordScore(BoardSize.FOUR, 40)
        writesDone()

        assertEquals("""{"volume":0.3}""", store.readShared()?.get("media").toString())
        assertEquals("kept", stored()?.get("later")?.asString())
        assertEquals(5, stored()?.get("best")?.get("3")?.asInt())
        assertEquals(77, stored()?.get("best")?.get("9")?.asInt())
        assertEquals(40, stored()?.get("best")?.get("4")?.asInt())

        seed("""{"doubles":"x"}""")
        preferences().saveSize(BoardSize.THREE)
        writesDone()
        assertEquals("""{"size":3,"best":{}}""", stored().toString())
    }
}
