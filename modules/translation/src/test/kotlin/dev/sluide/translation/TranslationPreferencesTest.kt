package dev.sluide.translation

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.translation.engine.Quality
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

class TranslationPreferencesTest {
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

    private fun preferences(delay: Duration = Duration.ZERO) = TranslationPreferences(store, delay, counting)

    /** Waits until the writes queued so far are done: the writer is a single thread. */
    private fun writesDone() {
        thread.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun seed(json: String) {
        val node = mapper.readTree(json)
        store.updateShared { root -> node.properties().forEach { (name, value) -> root.set(name, value) } }
    }

    private fun stored() = store.readShared()?.get("translation")

    @Test
    fun `defaults without a saved value`() {
        val preferences = preferences()

        assertNull(preferences.source)
        assertNull(preferences.target)
        assertEquals(Quality.MEDIUM, preferences.quality)
    }

    @Test
    fun `saved values are kept at once and read back by a new instance`() {
        val preferences = preferences()

        preferences.save("de", "sv", Quality.HIGH)

        assertEquals("de", preferences.source)
        writesDone()
        assertEquals("""{"source":"de","target":"sv","quality":"high"}""", stored().toString())
        val next = preferences()
        assertEquals("de", next.source)
        assertEquals("sv", next.target)
        assertEquals(Quality.HIGH, next.quality)
    }

    @Test
    fun `detect and no target are written as detect and an absent target`() {
        seed("""{"translation":{"source":"de","target":"sv","quality":"high"}}""")

        preferences().save(null, null, Quality.MEDIUM)
        writesDone()

        assertEquals("""{"source":"detect","quality":"medium"}""", stored().toString())
        assertNull(preferences().source)
        assertNull(preferences().target)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"translation":{"source":"","target":" ","quality":"low"}}""",
            """{"translation":{"source":7,"target":true,"quality":1}}""",
            """{"translation":{"source":"detect","target":null,"quality":"HIGH"}}""",
            """{"translation":["de","en"]}""",
            """{"translation":"de"}""",
        ],
    )
    fun `invalid values read as the defaults`(json: String) {
        seed(json)

        val preferences = preferences()

        assertNull(preferences.source)
        assertNull(preferences.target)
        assertEquals(Quality.MEDIUM, preferences.quality)
    }

    @Test
    fun `rapid saves end in one write of the latest values`() {
        // Long enough that the three saves always fall within it, even on a slow runner.
        val preferences = preferences(Duration.ofMillis(500))

        preferences.save(null, "en", Quality.MEDIUM)
        preferences.save("de", "en", Quality.MEDIUM)
        preferences.save("de", "sv", Quality.HIGH)
        writesDone()

        assertEquals(1, tasks.get())
        assertEquals("""{"source":"de","target":"sv","quality":"high"}""", stored().toString())
    }

    @Test
    fun `quitting writes the pending values at once, and later saves write at once`() {
        // The delayed write is still waiting when the app quits; its daemon thread would die with the JVM.
        val preferences = preferences(Duration.ofMinutes(1))
        preferences.save("de", "en", Quality.MEDIUM)

        preferences.destroy()

        assertEquals("""{"source":"de","target":"en","quality":"medium"}""", stored().toString())
        preferences.save(null, "sv", Quality.HIGH)
        assertEquals("""{"source":"detect","target":"sv","quality":"high"}""", stored().toString())
        assertEquals(1, tasks.get())
    }

    @Test
    fun `other fields of the shared file and of translation stay as they are`() {
        seed("""{"speech":{"speed":1.5},"translation":{"source":"de","later":"kept"}}""")

        preferences().save("en", "de", Quality.MEDIUM)
        writesDone()

        assertEquals("""{"speed":1.5}""", store.readShared()?.get("speech").toString())
        assertEquals("""{"source":"en","later":"kept","target":"de","quality":"medium"}""", stored().toString())
    }

    @Test
    fun `a translation field that is no object is replaced`() {
        seed("""{"translation":"de"}""")

        preferences().save(null, "en", Quality.MEDIUM)
        writesDone()

        assertEquals("""{"source":"detect","target":"en","quality":"medium"}""", stored().toString())
    }
}
