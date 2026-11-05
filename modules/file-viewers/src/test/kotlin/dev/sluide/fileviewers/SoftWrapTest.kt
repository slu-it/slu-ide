package dev.sluide.fileviewers

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

/** The user-wide soft wrap switch in `shared.json` ([SoftWrap]); the property is a plain one, so no display. */
class SoftWrapTest {
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
        // An interrupted write task still writes its value: let it finish before the temp folder goes.
        thread.shutdownNow()
        thread.awaitTermination(10, TimeUnit.SECONDS)
    }

    private fun softWrap(delay: Duration = Duration.ZERO) = SoftWrap(store, delay, counting)

    /** Waits until the writes queued so far are done: the writer is a single thread. */
    private fun writesDone() {
        thread.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun seed(json: String) {
        val node = mapper.readTree(json)
        store.updateShared { root -> node.properties().forEach { (name, value) -> root.set(name, value) } }
    }

    private fun stored() = store.readShared()?.get("editor")

    @Test
    fun `off without a saved value`() {
        assertFalse(softWrap().enabled.get())
    }

    @Test
    fun `on is written and read back by a new instance`() {
        val softWrap = softWrap()

        softWrap.enabled.set(true)

        writesDone()
        assertEquals("""{"softWrap":true}""", stored().toString())
        assertTrue(softWrap().enabled.get())
    }

    @Test
    fun `reads once and keeps the value in memory`() {
        seed("""{"editor":{"softWrap":true}}""")
        val softWrap = softWrap()
        assertTrue(softWrap.enabled.get())

        seed("""{"editor":{}}""")

        assertTrue(softWrap.enabled.get())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"editor":{"softWrap":"yes"}}""",
            """{"editor":{"softWrap":1}}""",
            """{"editor":{"softWrap":null}}""",
            """{"editor":{"softWrap":false}}""",
            """{"editor":[true]}""",
            """{"editor":"wrap"}""",
        ],
    )
    fun `a missing or invalid value reads as off`(json: String) {
        seed(json)

        assertFalse(softWrap().enabled.get())
    }

    @Test
    fun `off removes the key and an editor field left empty, keeping everything else`() {
        seed("""{"colors":{"saved":["#ff0000"]},"editor":{"softWrap":true,"later":"kept"}}""")
        val softWrap = softWrap()

        softWrap.enabled.set(false)
        writesDone()

        assertEquals("""{"saved":["#ff0000"]}""", store.readShared()?.get("colors").toString())
        assertEquals("""{"later":"kept"}""", stored().toString())

        seed("""{"editor":{"softWrap":true}}""")
        softWrap.enabled.set(true)
        softWrap.enabled.set(false)
        writesDone()
        assertNull(stored(), "an editor left empty goes")
        assertEquals("""{"saved":["#ff0000"]}""", store.readShared()?.get("colors").toString())
    }

    @Test
    fun `on keeps the other fields of editor and replaces an editor that is no object`() {
        seed("""{"editor":{"later":"kept"}}""")
        softWrap().enabled.set(true)
        writesDone()
        assertEquals("""{"later":"kept","softWrap":true}""", stored().toString())

        seed("""{"editor":"wrap"}""")
        softWrap().enabled.set(true)
        writesDone()
        assertEquals("""{"softWrap":true}""", stored().toString())
    }

    @Test
    fun `rapid changes end in one write of the latest value`() {
        // Long enough that the three changes always fall within it, even on a slow runner.
        val softWrap = softWrap(Duration.ofMillis(500))

        softWrap.enabled.set(true)
        softWrap.enabled.set(false)
        softWrap.enabled.set(true)
        writesDone()

        assertEquals(1, tasks.get())
        assertEquals("""{"softWrap":true}""", stored().toString())
    }

    @Test
    fun `quitting writes the pending value at once, and later changes write at once`() {
        // The delayed write is still waiting when the app quits; its daemon thread would die with the JVM.
        val softWrap = softWrap(Duration.ofMinutes(1))
        softWrap.enabled.set(true)

        softWrap.destroy()

        assertEquals("""{"softWrap":true}""", stored().toString())
        softWrap.enabled.set(false)
        assertNull(stored())
        assertEquals(1, tasks.get())
    }

    @Test
    fun `quitting without a change writes nothing`() {
        val softWrap = softWrap()
        softWrap.enabled.get()

        softWrap.destroy()

        assertNull(store.readShared())
    }
}
