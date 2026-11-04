package dev.sluide.speech

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
import kotlin.test.assertNull

class SpeechPreferencesTest {
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

    private fun preferences(delay: Duration = Duration.ZERO) = SpeechPreferences(store, delay, counting)

    /** Waits until the writes queued so far are done: the writer is a single thread. */
    private fun writesDone() {
        thread.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun seed(json: String) {
        val node = mapper.readTree(json)
        store.updateShared { root -> node.properties().forEach { (name, value) -> root.set(name, value) } }
    }

    private fun stored() = store.readShared()?.get("speech")

    @Test
    fun `defaults without a saved value`() {
        val preferences = preferences()

        assertNull(preferences.voiceId)
        assertEquals(1.0, preferences.speed)
    }

    @Test
    fun `saved values are kept at once and read back by a new instance`() {
        val preferences = preferences()

        preferences.save("bf_emma", 1.25)

        assertEquals("bf_emma", preferences.voiceId)
        assertEquals(1.25, preferences.speed)
        writesDone()
        assertEquals("""{"voice":"bf_emma","speed":1.25}""", stored().toString())
        assertEquals("bf_emma", preferences().voiceId)
        assertEquals(1.25, preferences().speed)
    }

    @Test
    fun `reads once and keeps the values in memory`() {
        seed("""{"speech":{"voice":"af_bella","speed":0.75}}""")
        val preferences = preferences()
        assertEquals("af_bella", preferences.voiceId)

        seed("""{"speech":{"voice":"af_heart","speed":2}}""")

        assertEquals("af_bella", preferences.voiceId)
        assertEquals(0.75, preferences.speed)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"speech":{"voice":"","speed":2.5}}""",
            """{"speech":{"voice":"  ","speed":0.25}}""",
            """{"speech":{"voice":7,"speed":"fast"}}""",
            """{"speech":{"voice":null,"speed":null}}""",
            """{"speech":["af_heart",1.5]}""",
            """{"speech":"loud"}""",
        ],
    )
    fun `invalid values read as the defaults`(json: String) {
        seed(json)

        val preferences = preferences()

        assertNull(preferences.voiceId)
        assertEquals(1.0, preferences.speed)
    }

    @Test
    fun `each value is checked on its own`() {
        seed("""{"speech":{"voice":"am_adam","speed":3}}""")

        val preferences = preferences()

        assertEquals("am_adam", preferences.voiceId)
        assertEquals(1.0, preferences.speed)
    }

    @Test
    fun `a saved speed is kept within the speeds`() {
        val preferences = preferences()

        preferences.save("af_heart", 4.0)
        assertEquals(2.0, preferences.speed)
        preferences.save("af_heart", 0.1)
        assertEquals(0.5, preferences.speed)
        preferences.save("af_heart", Double.NaN)
        assertEquals(1.0, preferences.speed)
    }

    @Test
    fun `rapid saves end in one write of the latest values`() {
        // Long enough that the three saves always fall within it, even on a slow runner.
        val preferences = preferences(Duration.ofMillis(500))

        preferences.save("af_heart", 1.0)
        preferences.save("af_bella", 1.5)
        preferences.save("bf_emma", 0.75)
        writesDone()

        assertEquals(1, tasks.get())
        assertEquals("""{"voice":"bf_emma","speed":0.75}""", stored().toString())
    }

    @Test
    fun `a save after a write writes again`() {
        val preferences = preferences()

        preferences.save("af_heart", 1.0)
        writesDone()
        preferences.save("af_bella", 2.0)
        writesDone()

        assertEquals(2, tasks.get())
        assertEquals("""{"voice":"af_bella","speed":2.0}""", stored().toString())
    }

    @Test
    fun `quitting writes the pending values at once, and later saves write at once`() {
        // The delayed write is still waiting when the app quits; its daemon thread would die with the JVM.
        val preferences = preferences(Duration.ofMinutes(1))
        preferences.save("af_heart", 1.5)

        preferences.destroy()

        assertEquals("""{"voice":"af_heart","speed":1.5}""", stored().toString())
        preferences.save("bf_emma", 0.5)
        assertEquals("""{"voice":"bf_emma","speed":0.5}""", stored().toString())
        assertEquals(1, tasks.get())
    }

    @Test
    fun `quitting without a pending save writes nothing`() {
        preferences().destroy()

        assertNull(store.readShared())
    }

    @Test
    fun `other fields of the shared file and of speech stay as they are`() {
        seed("""{"media":{"volume":0.3},"speech":{"voice":"af_heart","speed":1.0,"later":"kept"}}""")

        preferences().save("bf_emma", 1.25)
        writesDone()

        val root = store.readShared()
        assertEquals("""{"volume":0.3}""", root?.get("media").toString())
        assertEquals("""{"voice":"bf_emma","speed":1.25,"later":"kept"}""", stored().toString())
    }

    @Test
    fun `a speech field that is no object is replaced`() {
        seed("""{"speech":"loud"}""")

        preferences().save("af_heart", 1.0)
        writesDone()

        assertEquals("""{"voice":"af_heart","speed":1.0}""", stored().toString())
    }
}
