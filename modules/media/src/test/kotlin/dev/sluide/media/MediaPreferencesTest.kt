package dev.sluide.media

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

class MediaPreferencesTest {
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
        // An interrupted write task still writes its values: let it finish before the temp folder goes.
        thread.shutdownNow()
        thread.awaitTermination(10, TimeUnit.SECONDS)
    }

    private fun preferences(delay: Duration = Duration.ZERO) = MediaPreferences(store, delay, counting)

    /** Waits until the writes queued so far are done: the writer is a single thread. */
    private fun writesDone() {
        thread.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun seed(json: String) {
        val node = mapper.readTree(json)
        store.updateShared { root -> node.properties().forEach { (name, value) -> root.set(name, value) } }
    }

    private fun stored() = store.readShared()?.get("media")

    @Test
    fun `defaults without a saved value`() {
        val preferences = preferences()

        assertEquals(1.0, preferences.volume)
        assertFalse(preferences.muted)
    }

    @Test
    fun `saved values are kept at once and read back by a new instance`() {
        val preferences = preferences()

        preferences.save(0.4, true)

        assertEquals(0.4, preferences.volume)
        assertTrue(preferences.muted)
        writesDone()
        assertEquals("""{"volume":0.4,"muted":true}""", stored().toString())
        assertEquals(0.4, preferences().volume)
        assertTrue(preferences().muted)
    }

    @Test
    fun `reads once and keeps the values in memory`() {
        seed("""{"media":{"volume":0.3,"muted":true}}""")
        val preferences = preferences()
        assertEquals(0.3, preferences.volume)

        seed("""{"media":{"volume":0.9,"muted":false}}""")

        assertEquals(0.3, preferences.volume)
        assertTrue(preferences.muted)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"media":{"volume":1.5,"muted":"yes"}}""",
            """{"media":{"volume":-0.1,"muted":1}}""",
            """{"media":{"volume":"loud","muted":null}}""",
            """{"media":{"volume":null}}""",
            """{"media":[0.5,true]}""",
            """{"media":"quiet"}""",
        ],
    )
    fun `invalid values read as the defaults`(json: String) {
        seed(json)

        val preferences = preferences()

        assertEquals(1.0, preferences.volume)
        assertFalse(preferences.muted)
    }

    @Test
    fun `each value is checked on its own`() {
        seed("""{"media":{"volume":2,"muted":true}}""")

        val preferences = preferences()

        assertEquals(1.0, preferences.volume)
        assertTrue(preferences.muted)
    }

    @Test
    fun `a saved volume is kept within zero and one`() {
        val preferences = preferences()

        preferences.save(1.7, false)
        assertEquals(1.0, preferences.volume)
        preferences.save(-1.0, false)
        assertEquals(0.0, preferences.volume)
        preferences.save(Double.NaN, false)
        assertEquals(1.0, preferences.volume)
    }

    @Test
    fun `rapid saves end in one write of the latest values`() {
        // Long enough that the three saves always fall within it, even on a slow runner.
        val preferences = preferences(Duration.ofMillis(500))

        preferences.save(0.2, false)
        preferences.save(0.5, true)
        preferences.save(0.7, false)
        writesDone()

        assertEquals(1, tasks.get())
        assertEquals("""{"volume":0.7,"muted":false}""", stored().toString())
    }

    @Test
    fun `a save after a write writes again`() {
        val preferences = preferences()

        preferences.save(0.2, false)
        writesDone()
        preferences.save(0.6, true)
        writesDone()

        assertEquals(2, tasks.get())
        assertEquals("""{"volume":0.6,"muted":true}""", stored().toString())
    }

    @Test
    fun `quitting writes the pending values at once, and later saves write at once`() {
        // The delayed write is still waiting when the app quits; its daemon thread would die with the JVM.
        val preferences = preferences(Duration.ofMinutes(1))
        preferences.save(0.3, true)

        preferences.destroy()

        assertEquals("""{"volume":0.3,"muted":true}""", stored().toString())
        preferences.save(0.9, false)
        assertEquals("""{"volume":0.9,"muted":false}""", stored().toString())
        assertEquals(1, tasks.get())
    }

    @Test
    fun `quitting without a pending save writes nothing`() {
        preferences().destroy()

        assertNull(store.readShared())
    }

    @Test
    fun `other fields of the shared file and of media stay as they are`() {
        seed("""{"colors":{"saved":["#ff0000"]},"media":{"volume":0.3,"muted":false,"later":"kept"}}""")

        preferences().save(0.8, true)
        writesDone()

        val root = store.readShared()
        assertEquals("""{"saved":["#ff0000"]}""", root?.get("colors").toString())
        assertEquals("""{"volume":0.8,"muted":true,"later":"kept"}""", stored().toString())
    }

    @Test
    fun `a media field that is no object is replaced`() {
        seed("""{"media":"quiet"}""")

        preferences().save(0.5, false)
        writesDone()

        assertEquals("""{"volume":0.5,"muted":false}""", stored().toString())
    }
}
