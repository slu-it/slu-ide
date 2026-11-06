package dev.sluide.games.wordguess

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

class WordGuessPreferencesTest {
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

    private fun preferences(delay: Duration = Duration.ZERO) = WordGuessPreferences(store, delay, counting)

    private fun writesDone() {
        thread.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun seed(json: String) {
        val node = mapper.readTree(json)
        store.updateShared { root -> node.properties().forEach { (name, value) -> root.set(name, value) } }
    }

    private fun stored() = store.readShared()?.get("wordGuess")

    @Test
    fun `English without a saved value`() {
        assertEquals(Language.EN, preferences().lastLanguage)
    }

    @Test
    fun `a saved language applies at once and is read back by a new instance`() {
        val preferences = preferences()

        preferences.saveLanguage(Language.DE)

        assertEquals(Language.DE, preferences.lastLanguage)
        writesDone()
        assertEquals("""{"language":"de"}""", stored().toString())
        assertEquals(Language.DE, preferences().lastLanguage)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"wordGuess":{"language":"fr"}}""",
            """{"wordGuess":{"language":7}}""",
            """{"wordGuess":{"language":{"x":"de"}}}""",
            """{"wordGuess":{"language":["de"]}}""",
            """{"wordGuess":{"language":null}}""",
            """{"wordGuess":{}}""",
            """{"wordGuess":"de"}""",
        ],
    )
    fun `invalid values read as English`(json: String) {
        seed(json)

        assertEquals(Language.EN, preferences().lastLanguage)
    }

    @Test
    fun `reads once and keeps the value in memory`() {
        seed("""{"wordGuess":{"language":"de"}}""")
        val preferences = preferences()
        assertEquals(Language.DE, preferences.lastLanguage)

        seed("""{"wordGuess":{"language":"en"}}""")

        assertEquals(Language.DE, preferences.lastLanguage)
    }

    @Test
    fun `quitting writes the pending value at once`() {
        val preferences = preferences(Duration.ofMinutes(1))
        preferences.saveLanguage(Language.DE)

        preferences.destroy()

        assertEquals("de", stored()?.get("language")?.asString())
    }

    @Test
    fun `a save after quitting writes at once`() {
        val preferences = preferences(Duration.ofMinutes(1))
        preferences.destroy()

        preferences.saveLanguage(Language.DE)

        assertEquals("de", stored()?.get("language")?.asString())
        assertEquals(0, tasks.get())
    }

    @Test
    fun `rapid saves end in one write of the latest value`() {
        // Long enough that the saves always fall within it, even on a slow runner.
        val preferences = preferences(Duration.ofMillis(500))

        preferences.saveLanguage(Language.DE)
        preferences.saveLanguage(Language.EN)
        preferences.saveLanguage(Language.DE)
        writesDone()

        assertEquals(1, tasks.get())
        assertEquals("""{"language":"de"}""", stored().toString())
    }

    @Test
    fun `a save after a write writes again`() {
        val preferences = preferences()

        preferences.saveLanguage(Language.DE)
        writesDone()
        preferences.saveLanguage(Language.EN)
        writesDone()

        assertEquals(2, tasks.get())
        assertEquals("en", stored()?.get("language")?.asString())
    }

    @Test
    fun `quitting without a change writes nothing`() {
        preferences().destroy()

        assertNull(store.readShared())
    }

    @Test
    fun `other fields stay as they are and a non-object field is replaced`() {
        seed("""{"media":{"volume":0.3},"wordGuess":{"later":"kept"}}""")

        preferences().saveLanguage(Language.DE)
        writesDone()

        assertEquals("""{"volume":0.3}""", store.readShared()?.get("media").toString())
        assertEquals("kept", stored()?.get("later")?.asString())
        assertEquals("de", stored()?.get("language")?.asString())

        seed("""{"wordGuess":"de"}""")
        preferences().saveLanguage(Language.EN)
        writesDone()
        assertEquals("""{"language":"en"}""", stored().toString())
    }
}
