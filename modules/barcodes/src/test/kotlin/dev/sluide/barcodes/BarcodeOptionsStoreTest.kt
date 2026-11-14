package dev.sluide.barcodes

import dev.sluide.barcodes.engine.CodeFormat
import dev.sluide.barcodes.engine.GenerateOptions
import dev.sluide.barcodes.engine.QrEcc
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BarcodeOptionsStoreTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var data: ProjectDataStore
    private val thread: ExecutorService = Executors.newSingleThreadExecutor()
    private val tasks = AtomicInteger()
    private val counting = Executor { task -> tasks.incrementAndGet().also { thread.execute(task) } }
    private val mapper = JsonMapper()

    @BeforeEach
    fun setUp() {
        data = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
    }

    @AfterEach
    fun tearDown() {
        // An interrupted write task still writes its values: let it finish before the temp folder goes.
        thread.shutdownNow()
        thread.awaitTermination(10, TimeUnit.SECONDS)
    }

    private fun store(delay: Duration = Duration.ZERO) = BarcodeOptionsStore(data, delay, counting)

    /** Waits until the writes queued so far are done: the writer is a single thread. */
    private fun writesDone() {
        thread.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun seed(json: String) {
        val node = mapper.readTree(json)
        data.updateShared { root -> node.properties().forEach { (name, value) -> root.set(name, value) } }
    }

    private fun stored() = data.readShared()?.get("barcodes")

    @Test
    fun `nothing saved loads the defaults`() {
        assertEquals(CodeFormat.QR_CODE to GenerateOptions(), store().load())
    }

    @Test
    fun `defaults write nothing`() {
        store().save(CodeFormat.QR_CODE, GenerateOptions())
        writesDone()
        assertNull(stored())
    }

    @Test
    fun `every field makes a round trip`() {
        val options =
            GenerateOptions(
                qrEcc = QrEcc.H,
                aztecEccPercent = 50,
                pdf417Level = 5,
                sizePx = 1024,
                quietZone = 3,
                foreground = 0xFF112233.toInt(),
                background = 0x80AABBCC.toInt(),
            )
        val store = store()
        store.save(CodeFormat.PDF_417, options)
        assertEquals(CodeFormat.PDF_417 to options, store.load())
        writesDone()
        assertEquals(CodeFormat.PDF_417 to options, store().load())
    }

    @Test
    fun `only changed values are written and a reset removes them`() {
        val store = store()
        store.save(CodeFormat.AZTEC, GenerateOptions(sizePx = 1024))
        writesDone()
        assertEquals(setOf("format", "sizePx"), stored()?.propertyNames()?.toSet())
        store.save(CodeFormat.QR_CODE, GenerateOptions())
        writesDone()
        assertNull(stored())
    }

    @Test
    fun `a field of the wrong type is ignored`() {
        seed(
            """{"barcodes": {"format": 3, "qrEcc": "Z", "aztecEccPercent": "9", "sizePx": 1.5,
               "quietZone": true, "foreground": "red", "background": 7, "pdf417Level": 4}}""",
        )
        val (format, options) = store().load()
        assertEquals(CodeFormat.QR_CODE, format)
        assertEquals(GenerateOptions(pdf417Level = 4), options)
    }

    @Test
    fun `a format that cannot be generated is ignored`() {
        seed("""{"barcodes": {"format": "CODE_39"}}""")
        assertEquals(CodeFormat.QR_CODE, store().load().first)
    }

    @Test
    fun `out of range numbers are clamped`() {
        seed("""{"barcodes": {"sizePx": 100000, "quietZone": -4, "aztecEccPercent": 1, "pdf417Level": 99}}""")
        val options = store().load().second
        assertEquals(4096, options.sizePx)
        assertEquals(0, options.quietZone)
        assertEquals(5, options.aztecEccPercent)
        assertEquals(8, options.pdf417Level)
    }

    @Test
    fun `unknown content is kept on write`() {
        seed("""{"other": 1, "barcodes": {"future": "x"}}""")
        store().save(CodeFormat.DATA_MATRIX, GenerateOptions())
        writesDone()
        assertEquals("x", stored()?.get("future")?.asString())
        assertEquals("DATA_MATRIX", stored()?.get("format")?.asString())
        assertEquals(1, data.readShared()?.get("other")?.asInt())
    }

    @Test
    fun `reads once and keeps the values in memory`() {
        seed("""{"barcodes": {"sizePx": 1024}}""")
        val store = store()
        assertEquals(1024, store.load().second.sizePx)

        seed("""{"barcodes": {"sizePx": 2048}}""")

        assertEquals(1024, store.load().second.sizePx)
    }

    @Test
    fun `rapid saves end in one write of the latest values`() {
        // Long enough that the saves always fall within it, even on a slow runner.
        val store = store(Duration.ofMillis(500))

        store.save(CodeFormat.AZTEC, GenerateOptions(sizePx = 512 + 64))
        store.save(CodeFormat.AZTEC, GenerateOptions(sizePx = 512 + 128))
        store.save(CodeFormat.AZTEC, GenerateOptions(sizePx = 512 + 192))
        writesDone()

        assertEquals(1, tasks.get())
        assertEquals(704, stored()?.get("sizePx")?.asInt())
    }

    @Test
    fun `quitting writes the pending values at once, and later saves write at once`() {
        // The delayed write is still waiting when the app quits; its daemon thread would die with the JVM.
        val store = store(Duration.ofMinutes(1))
        store.save(CodeFormat.DATA_MATRIX, GenerateOptions())

        store.destroy()

        assertEquals("DATA_MATRIX", stored()?.get("format")?.asString())
        store.save(CodeFormat.AZTEC, GenerateOptions())
        assertEquals("AZTEC", stored()?.get("format")?.asString())
        assertEquals(1, tasks.get())
    }
}
