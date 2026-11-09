package dev.sluide.games.common

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
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

/** The read-once and delayed-write behavior shared by the games' preferences, with one string value as `v`. */
class StoredFieldTest {
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

    private fun field(delay: Duration = Duration.ZERO) =
        StoredField<String>(
            store = store,
            fieldName = "game",
            gameName = "Test game",
            writeDelay = delay,
            writer = counting,
            read = { node -> node?.get("v")?.takeIf { it.isString }?.asString() ?: "none" },
            write = { value, node -> node.put("v", value) },
        )

    private fun writesDone() {
        thread.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun seed(json: String) {
        val node = mapper.readTree(json)
        store.updateShared { root -> node.properties().forEach { (name, value) -> root.set(name, value) } }
    }

    private fun stored() = store.readShared()?.get("game")

    @Test
    fun `a missing or invalid field reads through the reader as null or the wrong type`() {
        assertEquals("none", field().value)
        seed("""{"game":"x"}""")
        assertEquals("none", field().value)
    }

    @Test
    fun `reads once and keeps the value in memory`() {
        seed("""{"game":{"v":"a"}}""")
        val field = field()
        assertEquals("a", field.value)

        seed("""{"game":{"v":"b"}}""")

        assertEquals("a", field.value)
    }

    @Test
    fun `a change applies at once and is written`() {
        val field = field()

        field.change { "x" }

        assertEquals("x", field.value)
        writesDone()
        assertEquals("""{"v":"x"}""", stored().toString())
    }

    @Test
    fun `quitting writes the pending value at once`() {
        val field = field(Duration.ofMinutes(1))
        field.change { "x" }

        field.destroy()

        assertEquals("x", stored()?.get("v")?.asString())
    }

    @Test
    fun `a change after quitting writes at once`() {
        val field = field(Duration.ofMinutes(1))
        field.destroy()

        field.change { "late" }

        assertEquals("late", stored()?.get("v")?.asString())
        assertEquals(0, tasks.get())
    }

    @Test
    fun `rapid changes end in one write of the latest value`() {
        // Long enough that the changes always fall within it, even on a slow runner.
        val field = field(Duration.ofMillis(500))

        field.change { "a" }
        field.change { "b" }
        field.change { "c" }
        writesDone()

        assertEquals(1, tasks.get())
        assertEquals("""{"v":"c"}""", stored().toString())
    }

    @Test
    fun `a change after a write writes again`() {
        val field = field()

        field.change { "a" }
        writesDone()
        field.change { "b" }
        writesDone()

        assertEquals(2, tasks.get())
        assertEquals("b", stored()?.get("v")?.asString())
    }

    @Test
    fun `a change that changes nothing schedules no write`() {
        val field = field()
        field.change { "a" }
        writesDone()
        val written = tasks.get()

        field.change { "a" }
        writesDone()

        assertEquals(written, tasks.get())
    }

    @Test
    fun `no write at all for first changes that change nothing, and none when quitting`() {
        val field = field()

        field.change { it }
        field.destroy()
        writesDone()

        assertEquals(0, tasks.get())
        assertNull(store.readShared())
    }

    @Test
    fun `other fields and other keys stay as they are and a non-object field is replaced`() {
        seed("""{"media":{"volume":0.3},"game":{"later":"kept"}}""")

        field().change { "x" }
        writesDone()

        assertEquals("""{"volume":0.3}""", store.readShared()?.get("media").toString())
        assertEquals("kept", stored()?.get("later")?.asString())
        assertEquals("x", stored()?.get("v")?.asString())

        seed("""{"game":"x"}""")
        field().change { "y" }
        writesDone()
        assertEquals("""{"v":"y"}""", stored().toString())
    }
}
