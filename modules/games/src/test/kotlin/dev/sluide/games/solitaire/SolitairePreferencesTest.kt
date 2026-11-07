package dev.sluide.games.solitaire

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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

class SolitairePreferencesTest {
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

    private fun preferences(delay: Duration = Duration.ZERO) = SolitairePreferences(store, delay, counting)

    private fun writesDone() {
        thread.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun seed(json: String) {
        val node = mapper.readTree(json)
        store.updateShared { root -> node.properties().forEach { (name, value) -> root.set(name, value) } }
    }

    private fun stored() = store.readShared()?.get("solitaire")

    @Test
    fun `Klondike drawing one card and no high score without saved values`() {
        val preferences = preferences()

        assertEquals(Mode.KLONDIKE_DRAW_1, preferences.lastMode)
        assertEquals(Mode.SPIDER_1, preferences.modeFor(Variant.SPIDER))
        assertNull(preferences.highScore(Mode.KLONDIKE_DRAW_1))
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `a saved mode applies at once and is read back`(mode: Mode) {
        val preferences = preferences()

        preferences.saveMode(mode)

        assertEquals(mode, preferences.lastMode)
        assertEquals(mode, preferences.modeFor(mode.variant))
        writesDone()
        assertEquals(mode, preferences().lastMode)
    }

    @Test
    fun `switching variants keeps the option of the other one`() {
        val preferences = preferences()
        preferences.saveMode(Mode.KLONDIKE_DRAW_3)
        preferences.saveMode(Mode.SPIDER_2)

        assertEquals(Mode.SPIDER_2, preferences.lastMode)
        assertEquals(Mode.KLONDIKE_DRAW_3, preferences.modeFor(Variant.KLONDIKE))
        assertEquals(Mode.FREECELL, preferences.modeFor(Variant.FREECELL))
        writesDone()
        assertEquals("""{"game":"spider","draw":3,"suits":2,"high":{}}""", stored().toString())
        val again = preferences()
        assertEquals(Mode.SPIDER_2, again.lastMode)
        assertEquals(Mode.KLONDIKE_DRAW_3, again.modeFor(Variant.KLONDIKE))
    }

    @Test
    fun `a high score is stored only when higher`() {
        val preferences = preferences()
        assertTrue(preferences.recordScore(Mode.KLONDIKE_DRAW_1, 4210))

        assertFalse(preferences.recordScore(Mode.KLONDIKE_DRAW_1, 4210))
        assertFalse(preferences.recordScore(Mode.KLONDIKE_DRAW_1, 100))
        assertTrue(preferences.recordScore(Mode.KLONDIKE_DRAW_1, 4300))

        assertEquals(4300, preferences.highScore(Mode.KLONDIKE_DRAW_1))
        assertNull(preferences.highScore(Mode.KLONDIKE_DRAW_3))
    }

    @Test
    fun `negative Spider scores are stored and read back`() {
        val preferences = preferences()

        assertTrue(preferences.recordScore(Mode.SPIDER_4, -120))
        assertTrue(preferences.recordScore(Mode.SPIDER_4, -50))
        assertFalse(preferences.recordScore(Mode.SPIDER_4, -80))

        writesDone()
        assertEquals(-50, preferences().highScore(Mode.SPIDER_4))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"solitaire":{"game":"chess","draw":2,"suits":3}}""",
            """{"solitaire":{"game":3,"draw":"3","suits":"2"}}""",
            """{"solitaire":{"game":"Spider","draw":1.5,"suits":4.5}}""",
            """{"solitaire":{"game":null,"draw":null,"suits":null}}""",
            """{"solitaire":{}}""",
            """{"solitaire":"x"}""",
        ],
    )
    fun `invalid values read as defaults`(json: String) {
        seed(json)
        val preferences = preferences()

        assertEquals(Mode.KLONDIKE_DRAW_1, preferences.lastMode)
        assertEquals(Mode.KLONDIKE_DRAW_1, preferences.modeFor(Variant.KLONDIKE))
        assertEquals(Mode.SPIDER_1, preferences.modeFor(Variant.SPIDER))
    }

    @Test
    fun `out of range options are written as defaults`() {
        seed("""{"solitaire":{"game":"chess","draw":2,"suits":3}}""")
        val preferences = preferences()

        preferences.saveMode(Mode.SPIDER_2)
        writesDone()
        assertEquals(1, stored()?.get("draw")?.asInt())

        seed("""{"solitaire":{"game":"chess","draw":2,"suits":3}}""")
        preferences().saveMode(Mode.KLONDIKE_DRAW_3)
        writesDone()
        assertEquals(1, stored()?.get("suits")?.asInt())
    }

    @Test
    fun `valid values are read`() {
        seed("""{"solitaire":{"game":"spider","draw":3,"suits":4,"high":{"klondike-1":4210,"spider-2":-8}}}""")
        val preferences = preferences()

        assertEquals(Mode.SPIDER_4, preferences.lastMode)
        assertEquals(Mode.KLONDIKE_DRAW_3, preferences.modeFor(Variant.KLONDIKE))
        assertEquals(4210, preferences.highScore(Mode.KLONDIKE_DRAW_1))
        assertEquals(-8, preferences.highScore(Mode.SPIDER_2))
    }

    @Test
    fun `invalid high scores are ignored`() {
        seed("""{"solitaire":{"high":{"klondike-1":4210,"klondike-3":"many","freecell":1.5,"spider-1":null,"x":5}}}""")
        val preferences = preferences()

        assertEquals(4210, preferences.highScore(Mode.KLONDIKE_DRAW_1))
        assertNull(preferences.highScore(Mode.KLONDIKE_DRAW_3))
        assertNull(preferences.highScore(Mode.FREECELL))
        assertNull(preferences.highScore(Mode.SPIDER_1))
    }

    @Test
    fun `reads once and keeps the value in memory`() {
        seed("""{"solitaire":{"game":"freecell"}}""")
        val preferences = preferences()
        assertEquals(Mode.FREECELL, preferences.lastMode)

        seed("""{"solitaire":{"game":"spider"}}""")

        assertEquals(Mode.FREECELL, preferences.lastMode)
    }

    @Test
    fun `quitting writes the pending value at once`() {
        val preferences = preferences(Duration.ofMinutes(1))
        preferences.saveMode(Mode.SPIDER_2)

        preferences.destroy()

        assertEquals("spider", stored()?.get("game")?.asString())
        assertEquals(2, stored()?.get("suits")?.asInt())
    }

    @Test
    fun `a change after quitting writes at once`() {
        val preferences = preferences(Duration.ofMinutes(1))
        preferences.destroy()

        preferences.recordScore(Mode.FREECELL, 52)

        assertEquals(52, stored()?.get("high")?.get("freecell")?.asInt())
        assertEquals(0, tasks.get())
    }

    @Test
    fun `rapid changes end in one write of the latest values`() {
        // Long enough that the changes always fall within it, even on a slow runner.
        val preferences = preferences(Duration.ofMillis(500))

        preferences.saveMode(Mode.KLONDIKE_DRAW_3)
        preferences.recordScore(Mode.KLONDIKE_DRAW_3, 100)
        preferences.recordScore(Mode.KLONDIKE_DRAW_3, 150)
        preferences.saveMode(Mode.SPIDER_4)
        writesDone()

        assertEquals(1, tasks.get())
        assertEquals("""{"game":"spider","draw":3,"suits":4,"high":{"klondike-3":150}}""", stored().toString())
    }

    @Test
    fun `a change that changes nothing schedules no write`() {
        val preferences = preferences()

        preferences.saveMode(Mode.KLONDIKE_DRAW_1)
        writesDone()

        assertEquals(0, tasks.get())
        assertNull(store.readShared())
    }

    @Test
    fun `other fields stay as they are and a non-object field is replaced`() {
        seed("""{"media":{"volume":0.3},"solitaire":{"later":"kept","high":{"freecell":5,"x":77}}}""")

        preferences().recordScore(Mode.SPIDER_1, 40)
        writesDone()

        assertEquals("""{"volume":0.3}""", store.readShared()?.get("media").toString())
        assertEquals("kept", stored()?.get("later")?.asString())
        assertEquals(5, stored()?.get("high")?.get("freecell")?.asInt())
        assertEquals(77, stored()?.get("high")?.get("x")?.asInt())
        assertEquals(40, stored()?.get("high")?.get("spider-1")?.asInt())

        seed("""{"solitaire":"x"}""")
        preferences().saveMode(Mode.FREECELL)
        writesDone()
        assertEquals("""{"game":"freecell","draw":1,"suits":1,"high":{}}""", stored().toString())
    }
}
