package dev.sluide.games.pipepuzzle

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
internal class PipePuzzlePreferencesTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var store: ProjectDataStore
    private val mapper = JsonMapper()

    @BeforeEach
    fun setUp() {
        store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
    }

    private fun preferences() = PipePuzzlePreferences(store, Duration.ZERO, Executor { it.run() })

    private fun seed(json: String) {
        val node = mapper.readTree(json)
        store.updateShared { root -> node.properties().forEach { (name, value) -> root.set(name, value) } }
    }

    private fun stored() = store.readShared()?.get("pipePuzzle")

    @Test
    fun `defaults without saved values`() {
        val preferences = preferences()

        assertEquals(Mode.NETWORK, preferences.lastMode)
        assertEquals(BoardSize.S7, preferences.lastSize)
        assertEquals(Look.WATER, preferences.lastLook)
        assertNull(preferences.best(Mode.NETWORK, BoardSize.S7))
        assertNull(store.readShared())
    }

    @Test
    fun `saved values are read`() {
        seed("""{"pipePuzzle":{"mode":"path","size":12,"look":"light","best":{"path-12":140000}}}""")
        val preferences = preferences()

        assertEquals(Mode.PATH, preferences.lastMode)
        assertEquals(BoardSize.S12, preferences.lastSize)
        assertEquals(Look.LIGHT, preferences.lastLook)
        assertEquals(140000L, preferences.best(Mode.PATH, BoardSize.S12))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"pipePuzzle":{"mode":"maze","size":8,"look":"fire"}}""",
            """{"pipePuzzle":{"mode":1,"size":"7","look":null}}""",
            """{"pipePuzzle":{"size":6.5}}""",
            """{"pipePuzzle":"pipes"}""",
            """{"pipePuzzle":{}}""",
        ],
    )
    fun `invalid values read as the defaults`(json: String) {
        seed(json)
        val preferences = preferences()

        assertEquals(Mode.NETWORK, preferences.lastMode)
        assertEquals(BoardSize.S7, preferences.lastSize)
        assertEquals(Look.WATER, preferences.lastLook)
    }

    @Test
    fun `invalid best entries are dropped and valid ones kept`() {
        seed(
            """{"pipePuzzle":{"best":{"network-7":83000,"network-8":5,"maze-7":5,"path-5":0,"path-7":-3,""" +
                """"path-9":"fast","path-12":1.5}}}""",
        )
        val preferences = preferences()

        assertEquals(83000L, preferences.best(Mode.NETWORK, BoardSize.S7))
        assertNull(preferences.best(Mode.PATH, BoardSize.S5))
        assertNull(preferences.best(Mode.PATH, BoardSize.S7))
        assertNull(preferences.best(Mode.PATH, BoardSize.S9))
        assertNull(preferences.best(Mode.PATH, BoardSize.S12))
    }

    @Test
    fun `values round trip`() {
        val preferences = preferences()
        preferences.lastMode = Mode.PATH
        preferences.lastSize = BoardSize.S9
        preferences.lastLook = Look.POWER
        assertTrue(preferences.recordBest(Mode.PATH, BoardSize.S9, 90000))
        assertTrue(preferences.recordBest(Mode.NETWORK, BoardSize.S5, 12000))

        assertEquals(
            mapper.readTree(
                """{"mode":"path","size":9,"look":"power","best":{"network-5":12000,"path-9":90000}}""",
            ),
            stored(),
        )
        val again = preferences()
        assertEquals(Mode.PATH, again.lastMode)
        assertEquals(BoardSize.S9, again.lastSize)
        assertEquals(Look.POWER, again.lastLook)
        assertEquals(90000L, again.best(Mode.PATH, BoardSize.S9))
        assertEquals(12000L, again.best(Mode.NETWORK, BoardSize.S5))
    }

    @Test
    fun `only a lower time is stored`() {
        val preferences = preferences()
        assertTrue(preferences.recordBest(Mode.NETWORK, BoardSize.S7, 60000))

        assertFalse(preferences.recordBest(Mode.NETWORK, BoardSize.S7, 60000))
        assertFalse(preferences.recordBest(Mode.NETWORK, BoardSize.S7, 70000))
        assertFalse(preferences.recordBest(Mode.NETWORK, BoardSize.S7, 0))
        assertTrue(preferences.recordBest(Mode.NETWORK, BoardSize.S7, 59000))
        assertTrue(preferences.recordBest(Mode.PATH, BoardSize.S7, 80000))

        assertEquals(59000L, preferences.best(Mode.NETWORK, BoardSize.S7))
        assertEquals(80000L, preferences.best(Mode.PATH, BoardSize.S7))
    }

    @Test
    fun `other fields stay as they are`() {
        seed("""{"media":{"volume":0.3},"pipePuzzle":{"later":"kept","mode":"path"}}""")

        preferences().lastLook = Look.LIGHT

        assertEquals("""{"volume":0.3}""", store.readShared()?.get("media").toString())
        assertEquals("kept", stored()?.get("later")?.asString())
        assertEquals("path", stored()?.get("mode")?.asString())
        assertEquals("light", stored()?.get("look")?.asString())
    }

    @Test
    fun `quitting flushes a pending write`() {
        val queued = mutableListOf<Runnable>()
        val preferences = PipePuzzlePreferences(store, Duration.ofMinutes(1), Executor { queued += it })
        preferences.lastSize = BoardSize.S5

        preferences.destroy()

        assertEquals(5, stored()?.get("size")?.asInt())
    }
}
