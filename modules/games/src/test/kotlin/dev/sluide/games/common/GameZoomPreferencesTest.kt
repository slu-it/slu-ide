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
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class GameZoomPreferencesTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var store: ProjectDataStore
    private val thread: ExecutorService = NamedThreads.single("sluide-test")
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

    private fun preferences(delay: Duration = Duration.ZERO) = GameZoomPreferences(store, delay, thread)

    private fun writesDone() {
        thread.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun seed(json: String) {
        val node = mapper.readTree(json)
        store.updateShared { root -> node.properties().forEach { (name, value) -> root.set(name, value) } }
    }

    private fun stored() = store.readShared()?.get("gameZoom")

    @Test
    fun `a game without a saved zoom is at Fit`() {
        assertEquals(1.0, preferences().zoom("minesweeper", ZoomRange.ABOVE_FIT))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"gameZoom":{"minesweeper":"0.5"}}""",
            """{"gameZoom":{"minesweeper":null}}""",
            """{"gameZoom":{"minesweeper":{}}}""",
            """{"gameZoom":{"minesweeper":1e400}}""",
            """{"gameZoom":{"sudoku":0.5}}""",
            """{"gameZoom":[0.5]}""",
            """{"gameZoom":0.5}""",
        ],
    )
    fun `an invalid zoom reads as Fit`(json: String) {
        seed(json)

        assertEquals(1.0, preferences().zoom("minesweeper", ZoomRange.ABOVE_FIT))
    }

    @Test
    fun `a zoom out of the game's range reads as its nearest bound`() {
        seed("""{"gameZoom":{"minesweeper":1.5,"sudoku":0.1,"orbitGuard":3}}""")
        val preferences = preferences()

        assertEquals(1.5, preferences.zoom("minesweeper", ZoomRange.ABOVE_FIT))
        assertEquals(1.0, preferences.zoom("minesweeper", ZoomRange.UP_TO_FIT))
        assertEquals(0.25, preferences.zoom("sudoku", ZoomRange.ABOVE_FIT))
        assertEquals(1.0, preferences.zoom("orbitGuard", ZoomRange.UP_TO_FIT))
    }

    @Test
    fun `a saved zoom applies at once and is read back`() {
        val preferences = preferences()

        preferences.setZoom("minesweeper", 0.75)
        preferences.setZoom("sudoku", 1.25)

        assertEquals(0.75, preferences.zoom("minesweeper", ZoomRange.ABOVE_FIT))
        writesDone()
        assertEquals("""{"minesweeper":0.75,"sudoku":1.25}""", stored().toString())
        assertEquals(1.25, preferences().zoom("sudoku", ZoomRange.ABOVE_FIT))
    }

    @Test
    fun `a zoom that is no number is not saved`() {
        val preferences = preferences()
        preferences.setZoom("minesweeper", 0.5)

        preferences.setZoom("minesweeper", Double.NaN)

        assertEquals(0.5, preferences.zoom("minesweeper", ZoomRange.ABOVE_FIT))
    }

    @Test
    fun `other games' zooms and other fields stay as they are`() {
        seed("""{"media":{"volume":0.3},"gameZoom":{"later":"kept","sudoku":0.5}}""")

        preferences().setZoom("minesweeper", 2.0)
        writesDone()

        assertEquals("""{"volume":0.3}""", store.readShared()?.get("media").toString())
        assertEquals("""{"later":"kept","sudoku":0.5,"minesweeper":2.0}""", stored().toString())
    }

    @Test
    fun `quitting writes the pending zoom at once`() {
        val preferences = preferences(Duration.ofMinutes(1))
        preferences.setZoom("minesweeper", 0.25)

        preferences.destroy()

        assertEquals(0.25, stored()?.get("minesweeper")?.asDouble())
    }
}
