package dev.sluide.games.minigolf

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The delayed writing is that of `StoredField` and tested there. */
internal class MiniGolfPreferencesTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var store: ProjectDataStore
    private val mapper = JsonMapper()

    @BeforeEach
    fun setUp() {
        store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
    }

    private fun preferences() = MiniGolfPreferences(store, Duration.ZERO, Executor { it.run() })

    private fun seed(json: String) {
        val node = mapper.readTree(json)
        store.updateShared { root -> node.properties().forEach { (name, value) -> root.set(name, value) } }
    }

    private fun stored() = store.readShared()?.get("miniGolf")

    @Test
    fun `nothing is stored without saved values`() {
        val preferences = preferences()

        assertNull(preferences.bestTotal)
        assertEquals(listOf(null, null, null), preferences.bestHoles(3))
    }

    @Test
    fun `recorded values round trip`() {
        val preferences = preferences()

        assertTrue(preferences.recordHole(2, 3))
        assertTrue(preferences.recordHole(0, 2))
        assertTrue(preferences.recordTotal(49))

        assertEquals("""{"bestHoles":[2,null,3],"bestTotal":49}""", stored().toString())
        val again = preferences()
        assertEquals(49, again.bestTotal)
        assertEquals(listOf(2, null, 3, null), again.bestHoles(4))
    }

    @Test
    fun `only a lower score is stored`() {
        val preferences = preferences()
        assertTrue(preferences.recordHole(1, 4))
        assertTrue(preferences.recordTotal(60))

        assertFalse(preferences.recordHole(1, 4))
        assertFalse(preferences.recordHole(1, 5))
        assertTrue(preferences.recordHole(1, 3))
        assertFalse(preferences.recordTotal(60))
        assertFalse(preferences.recordTotal(61))
        assertTrue(preferences.recordTotal(59))

        assertEquals(3, preferences.bestHoles(2)[1])
        assertEquals(59, preferences.bestTotal)
    }

    @Test
    fun `scores that are no positive count are refused`() {
        val preferences = preferences()

        assertFalse(preferences.recordHole(0, 0))
        assertFalse(preferences.recordHole(-1, 2))
        assertFalse(preferences.recordTotal(0))

        assertNull(preferences.bestTotal)
        assertNull(store.readShared())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"miniGolf":{"bestTotal":0,"bestHoles":[0,-2,"x",1.5,null,true]}}""",
            """{"miniGolf":{"bestTotal":"49","bestHoles":"2,2"}}""",
            """{"miniGolf":{"bestTotal":4.5,"bestHoles":{"a":2}}}""",
            """{"miniGolf":"golf"}""",
            """{"miniGolf":{}}""",
        ],
    )
    fun `invalid values read as missing`(json: String) {
        seed(json)
        val preferences = preferences()

        assertNull(preferences.bestTotal)
        assertEquals(listOf(null, null, null, null, null, null), preferences.bestHoles(6))
    }

    @Test
    fun `valid entries next to invalid ones are kept and a short array pads with null`() {
        seed("""{"miniGolf":{"bestTotal":49,"bestHoles":[2,"x",3]}}""")
        val preferences = preferences()

        assertEquals(49, preferences.bestTotal)
        assertEquals(listOf(2, null, 3, null, null), preferences.bestHoles(5))
        assertEquals(listOf(2), preferences.bestHoles(1))
    }

    @Test
    fun `other fields stay as they are`() {
        seed("""{"media":{"volume":0.3},"miniGolf":{"later":"kept","bestTotal":50}}""")

        preferences().recordHole(0, 2)

        assertEquals("""{"volume":0.3}""", store.readShared()?.get("media").toString())
        assertEquals("kept", stored()?.get("later")?.asString())
        assertEquals(50, stored()?.get("bestTotal")?.asInt())
        assertEquals("[2]", stored()?.get("bestHoles").toString())
    }

    @Test
    fun `quitting flushes a pending write`() {
        val queued = mutableListOf<Runnable>()
        val preferences = MiniGolfPreferences(store, Duration.ofMinutes(1), Executor { queued += it })
        preferences.recordTotal(48)

        preferences.destroy()

        assertEquals(48, stored()?.get("bestTotal")?.asInt())
    }
}
