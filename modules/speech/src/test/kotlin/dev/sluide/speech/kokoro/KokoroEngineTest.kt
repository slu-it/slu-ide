package dev.sluide.speech.kokoro

import dev.sluide.settings.SettingsStore
import dev.sluide.speech.engine.SetupProblem
import dev.sluide.speech.engine.SpeechChunk
import dev.sluide.speech.engine.SpeechException
import dev.sluide.speech.install.KokoroVariant
import dev.sluide.speech.install.KokoroVoicesFile
import dev.sluide.speech.install.ModelStore
import dev.sluide.speech.settings.SpeechSettings
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KokoroEngineTest {
    @TempDir
    lateinit var dir: Path

    private class FakeModel(
        val file: Path,
        val calls: MutableList<Int>,
        val concurrent: AtomicInteger,
        val maxConcurrent: AtomicInteger,
        val onRun: () -> Unit,
    ) : KokoroModel {
        @Volatile var closed = false

        override fun run(
            ids: LongArray,
            style: FloatArray,
            speed: Float,
        ): Pair<FloatArray, FloatArray> {
            val now = concurrent.incrementAndGet()
            maxConcurrent.accumulateAndGet(now, ::maxOf)
            try {
                onRun()
                calls.add(ids.size)
                Thread.sleep(5) // keeps the call open so that an unserialized second call would overlap
                return FloatArray(ids.size * 100) { 0.5f } to FloatArray(ids.size + 2) { 1f }
            } finally {
                concurrent.decrementAndGet()
            }
        }

        override fun close() {
            closed = true
        }
    }

    private val calls = CopyOnWriteArrayList<Int>()
    private val models = CopyOnWriteArrayList<FakeModel>()
    private val concurrent = AtomicInteger()
    private val maxConcurrent = AtomicInteger()
    private var onRun: () -> Unit = {}
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private val settings by lazy { SpeechSettings(SettingsStore(dir.resolve("settings.properties"))) }
    private val store by lazy { ModelStore(dir.resolve("models")) }
    private val espeak: Path by lazy {
        dir.resolve("espeak-ng").also {
            Files.writeString(it, "#!/bin/sh\n")
            it.toFile().setExecutable(true)
        }
    }

    private fun sparse(
        file: Path,
        bytes: Long,
    ) {
        Files.createDirectories(file.parent)
        RandomAccessFile(file.toFile(), "rw").use { it.setLength(bytes) }
    }

    private fun install(v: KokoroVariant = KokoroVariant.FP32) {
        sparse(store.modelFile(v), v.bytes)
        sparse(store.voicesFile(), KokoroVoicesFile.BYTES)
    }

    private fun engine(
        espeakFound: Boolean = true,
        idleMillis: Long = 60_000,
    ): KokoroEngine {
        val locator =
            EspeakLocator(
                configuredPath = { if (espeakFound) espeak.toString() else "" },
                loginShellLookup = { null },
                fallbackDirs = emptyList(),
            )
        val tokenizer = KokoroTokenizer({ word, _ -> word.lowercase().filter { it.isLetter() } }, KokoroVocab.load())
        return KokoroEngine(
            settings,
            store,
            locator,
            tokenizer,
            { StyleSource { _, _ -> FloatArray(256) } },
            { file -> FakeModel(file, calls, concurrent, maxConcurrent) { onRun() }.also { models.add(it) } },
            scheduler,
            idleMillis,
        )
    }

    private val voice = KokoroVoices.all.first()

    private fun chunk(text: String) = SpeechChunk(100, 100 + text.length, 100, 100 + text.length, text)

    @Test
    fun `problems lists a missing model and a missing espeak, and nothing when ready`() {
        val missing = engine(espeakFound = false).problems()
        assertEquals(
            listOf(
                SetupProblem.ModelMissing("kokoro", "FP32", KokoroVariant.FP32.bytes + KokoroVoicesFile.BYTES),
                SetupProblem.ToolMissing(
                    "espeak-ng",
                    "Install it with `brew install espeak-ng` (macOS) or `sudo apt install espeak-ng` " +
                        "(Debian/Ubuntu), or set its path in Settings → Speech.",
                ),
            ),
            missing,
        )
        install()
        assertEquals(emptyList(), engine().problems())
        assertTrue(models.isEmpty())
    }

    @Test
    fun `synthesize without model or espeak says why`() {
        val e = assertFailsWith<SpeechException> { engine().synthesize(chunk("hello"), voice, 1.0) }
        assertEquals("The Kokoro voice model is not installed.", e.message)
        install()
        val e2 = assertFailsWith<SpeechException> { engine(espeakFound = false).synthesize(chunk("hello"), voice, 1.0) }
        assertEquals("espeak-ng not found.", e2.message)
    }

    @Test
    fun `synthesizes audio with word timings`() {
        install()
        val result = engine().synthesize(chunk("hello world"), voice, 1.0)
        // 10 letters + a space = 11 tokens, 13 frames of 1 over 1100 samples
        assertEquals(1100, result.samples.size)
        assertEquals(24_000, result.sampleRate)
        assertEquals(2, result.words.size)
        assertEquals(100, result.words[0].start)
        assertEquals(111, result.words[1].end)
        assertTrue(result.words[0].startSample < result.words[0].endSample)
        assertTrue(result.words[0].endSample <= result.words[1].startSample)
        assertEquals(1015, result.words[1].endSample) // before the closing pad: 12 of 13 frames
    }

    @Test
    fun `a chunk without tokens gives no samples and zero-length timings`() {
        install()
        val result = engine().synthesize(chunk("123"), voice, 1.0)
        assertEquals(0, result.samples.size)
        assertTrue(result.words.all { it.startSample == it.endSample })
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a long chunk is split, concatenated and timed with a running offset`() {
        install()
        val text = List(200) { "hello" }.joinToString(" ")
        val result = engine().synthesize(chunk(text), voice, 1.0)
        assertTrue(calls.size >= 3)
        assertTrue(calls.all { it <= 510 })
        assertEquals(calls.sum() * 100, result.samples.size)
        assertEquals(200, result.words.size)
        assertTrue(result.words.last().endSample in result.samples.size - 200..result.samples.size)
        assertTrue(result.words.zipWithNext().all { (a, b) -> a.endSample <= b.startSample })
        assertTrue(result.words.all { it.endSample > it.startSample })
    }

    @Test
    fun `an interrupt between pieces ends the synthesis`() {
        install()
        onRun = { if (calls.isEmpty()) Thread.currentThread().interrupt() }
        val text = List(200) { "hello" }.joinToString(" ")
        assertFailsWith<InterruptedException> { engine().synthesize(chunk(text), voice, 1.0) }
        assertEquals(1, calls.size)
    }

    @Test
    fun `a variant change loads the other model and closes the first`() {
        install()
        install(KokoroVariant.INT8)
        val engine = engine()
        engine.synthesize(chunk("hello"), voice, 1.0)
        engine.synthesize(chunk("hello"), voice, 1.0)
        assertEquals(1, models.size)
        settings.apply("", KokoroVariant.INT8)
        engine.synthesize(chunk("hello"), voice, 1.0)
        assertEquals(2, models.size)
        assertTrue(models[0].closed)
        assertEquals(store.modelFile(KokoroVariant.INT8), models[1].file)
    }

    @Test
    fun `the model is unloaded after the idle time and loaded again on demand`() {
        install()
        val engine = engine(idleMillis = 50)
        engine.synthesize(chunk("hello"), voice, 1.0)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!models[0].closed && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(models[0].closed)
        engine.synthesize(chunk("hello"), voice, 1.0)
        assertEquals(2, models.size)
    }

    @Test
    fun `destroy closes the model`() {
        install()
        val engine = engine()
        engine.synthesize(chunk("hello"), voice, 1.0)
        engine.destroy()
        assertTrue(models[0].closed)
    }

    @Test
    fun `concurrent synthesize calls run one after the other`() {
        install()
        val engine = engine()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = List(4) { pool.submit { engine.synthesize(chunk("hello"), voice, 1.0) } }
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, maxConcurrent.get())
        assertEquals(4, calls.size)
    }
}
