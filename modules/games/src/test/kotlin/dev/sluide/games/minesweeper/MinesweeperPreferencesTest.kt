package dev.sluide.games.minesweeper

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

class MinesweeperPreferencesTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var store: ProjectDataStore
    private val thread: ExecutorService = NamedThreads.single("sluide-test")
    private val tasks = AtomicInteger()
    private val counting = Executor { task -> tasks.incrementAndGet().also { thread.execute(task) } }
    private val mapper = JsonMapper()

    private val beginner = SizeChoice.OfPreset(Preset.BEGINNER)

    @BeforeEach
    fun setUp() {
        store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
    }

    @AfterEach
    fun tearDown() {
        // An interrupted write task still writes its values: let it finish before the temp folder goes.
        thread.shutdownNow()
        thread.awaitTermination(10, TimeUnit.SECONDS)
    }

    private fun preferences(delay: Duration = Duration.ZERO) = MinesweeperPreferences(store, delay, counting)

    /** Waits until the writes queued so far are done: the writer is a single thread. */
    private fun writesDone() {
        thread.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun seed(json: String) {
        val node = mapper.readTree(json)
        store.updateShared { root -> node.properties().forEach { (name, value) -> root.set(name, value) } }
    }

    private fun stored() = store.readShared()?.get("minesweeper")

    @Test
    fun `defaults without a saved value`() {
        val preferences = preferences()

        assertEquals(beginner, preferences.lastSize)
        assertEquals(BoardSize(20, 12, 40), preferences.lastCustom)
        Preset.entries.forEach { assertNull(preferences.bestTime(it)) }
    }

    @Test
    fun `a saved preset is kept at once and read back by a new instance`() {
        val preferences = preferences()

        preferences.saveSize(SizeChoice.OfPreset(Preset.EXPERT))

        assertEquals(SizeChoice.OfPreset(Preset.EXPERT), preferences.lastSize)
        writesDone()
        assertEquals(
            """{"size":"expert","custom":{"width":20,"height":12,"mines":40},"best":{}}""",
            stored().toString(),
        )
        assertEquals(SizeChoice.OfPreset(Preset.EXPERT), preferences().lastSize)
    }

    @Test
    fun `a custom size also becomes the last custom and survives a preset choice`() {
        val preferences = preferences()
        val custom = BoardSize(30, 20, 77)

        preferences.saveSize(SizeChoice.Custom(custom))
        writesDone()
        assertEquals(SizeChoice.Custom(custom), preferences().lastSize)
        assertEquals(custom, preferences().lastCustom)

        preferences.saveSize(SizeChoice.OfPreset(Preset.INTERMEDIATE))
        writesDone()
        assertEquals(SizeChoice.OfPreset(Preset.INTERMEDIATE), preferences().lastSize)
        assertEquals(custom, preferences().lastCustom)
    }

    @Test
    fun `recordWin stores only faster times`() {
        val preferences = preferences()

        assertTrue(preferences.recordWin(Preset.BEGINNER, 50_000))
        assertFalse(preferences.recordWin(Preset.BEGINNER, 50_000))
        assertFalse(preferences.recordWin(Preset.BEGINNER, 60_000))
        assertFalse(preferences.recordWin(Preset.INTERMEDIATE, 0))
        assertTrue(preferences.recordWin(Preset.BEGINNER, 49_999))
        assertTrue(preferences.recordWin(Preset.EXPERT, 300_000))

        assertEquals(49_999L, preferences.bestTime(Preset.BEGINNER))
        assertNull(preferences.bestTime(Preset.INTERMEDIATE))
        writesDone()
        assertEquals(49_999L, preferences().bestTime(Preset.BEGINNER))
        assertEquals(300_000L, preferences().bestTime(Preset.EXPERT))
    }

    @Test
    fun `reads once and keeps the values in memory`() {
        seed("""{"minesweeper":{"size":"intermediate","best":{"expert":1000}}}""")
        val preferences = preferences()
        assertEquals(SizeChoice.OfPreset(Preset.INTERMEDIATE), preferences.lastSize)

        seed("""{"minesweeper":{"size":"expert","best":{"expert":5}}}""")

        assertEquals(SizeChoice.OfPreset(Preset.INTERMEDIATE), preferences.lastSize)
        assertEquals(1000L, preferences.bestTime(Preset.EXPERT))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"minesweeper":{"size":"huge","custom":{"width":"x"},"best":{"beginner":"fast"}}}""",
            """{"minesweeper":{"size":7,"best":{"beginner":-5,"expert":1.5}}}""",
            """{"minesweeper":{"size":"custom"}}""",
            """{"minesweeper":{"size":"custom","custom":{"width":9,"height":9}}}""",
            """{"minesweeper":{"size":null,"custom":null,"best":null}}""",
            """{"minesweeper":{"size":{}}}""",
            """{"minesweeper":{"size":["expert"]}}""",
            """{"minesweeper":["beginner"]}""",
            """{"minesweeper":"beginner"}""",
        ],
    )
    fun `invalid values read as the defaults`(json: String) {
        seed(json)

        val preferences = preferences()

        assertEquals(beginner, preferences.lastSize)
        assertEquals(BoardSize(20, 12, 40), preferences.lastCustom)
        Preset.entries.forEach { assertNull(preferences.bestTime(it)) }
    }

    @Test
    fun `stored custom values are clamped`() {
        seed("""{"minesweeper":{"size":"custom","custom":{"width":500,"height":2,"mines":9999}}}""")

        val preferences = preferences()

        assertEquals(SizeChoice.Custom(BoardSize(50, 5, 241)), preferences.lastSize)
        assertEquals(BoardSize(50, 5, 241), preferences.lastCustom)
    }

    @Test
    fun `each best time is checked on its own`() {
        seed("""{"minesweeper":{"best":{"beginner":"fast","intermediate":4000,"expert":0}}}""")

        val preferences = preferences()

        assertNull(preferences.bestTime(Preset.BEGINNER))
        assertEquals(4000L, preferences.bestTime(Preset.INTERMEDIATE))
        assertNull(preferences.bestTime(Preset.EXPERT))
    }

    @Test
    fun `rapid saves end in one write of the latest values`() {
        // Long enough that the saves always fall within it, even on a slow runner.
        val preferences = preferences(Duration.ofMillis(500))

        preferences.saveSize(SizeChoice.OfPreset(Preset.EXPERT))
        preferences.recordWin(Preset.BEGINNER, 9000)
        preferences.saveSize(SizeChoice.OfPreset(Preset.INTERMEDIATE))
        writesDone()

        assertEquals(1, tasks.get())
        assertEquals(
            """{"size":"intermediate","custom":{"width":20,"height":12,"mines":40},"best":{"beginner":9000}}""",
            stored().toString(),
        )
    }

    @Test
    fun `a save after a write writes again`() {
        val preferences = preferences()

        preferences.saveSize(SizeChoice.OfPreset(Preset.EXPERT))
        writesDone()
        preferences.saveSize(beginner)
        writesDone()

        assertEquals(2, tasks.get())
        assertEquals("beginner", stored()?.get("size")?.asString())
    }

    @Test
    fun `quitting writes the pending values at once, and later changes write at once`() {
        // The delayed write is still waiting when the app quits; its daemon thread would die with the JVM.
        val preferences = preferences(Duration.ofMinutes(1))
        preferences.saveSize(SizeChoice.OfPreset(Preset.EXPERT))

        preferences.destroy()

        assertEquals("expert", stored()?.get("size")?.asString())
        preferences.recordWin(Preset.EXPERT, 1234)
        assertEquals(1234L, stored()?.get("best")?.get("expert")?.asLong())
        assertEquals(1, tasks.get())
    }

    @Test
    fun `quitting without a pending change writes nothing`() {
        preferences().destroy()

        assertNull(store.readShared())
    }

    @Test
    fun `other fields of the shared file and of minesweeper stay as they are`() {
        seed(
            """{"media":{"volume":0.3},"minesweeper":{"size":"beginner","later":"kept","best":{"beginner":900,"x":1}}}""",
        )

        val preferences = preferences()
        preferences.recordWin(Preset.EXPERT, 5000)
        writesDone()

        assertEquals("""{"volume":0.3}""", store.readShared()?.get("media").toString())
        val node = stored()
        assertEquals("kept", node?.get("later")?.asString())
        assertEquals("""{"beginner":900,"x":1,"expert":5000}""", node?.get("best").toString())
    }

    @Test
    fun `a minesweeper field that is no object is replaced`() {
        seed("""{"minesweeper":"beginner"}""")

        preferences().saveSize(SizeChoice.OfPreset(Preset.EXPERT))
        writesDone()

        assertEquals("expert", stored()?.get("size")?.asString())
    }
}
