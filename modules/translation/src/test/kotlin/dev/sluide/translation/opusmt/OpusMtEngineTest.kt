package dev.sluide.translation.opusmt

import dev.sluide.translation.engine.Language
import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.Quality
import dev.sluide.translation.engine.Route
import dev.sluide.translation.engine.TranslationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class OpusMtEngineTest {
    @TempDir
    lateinit var root: Path

    /** Counts a token per word (and one for `</s>`); "translates" by naming its pair, after running [onTranslate]. */
    private inner class FakeTranslator(
        val setup: PairSetup,
    ) : PairTranslator {
        val pair =
            setup.files.encoder.parent.parent.fileName
                .toString()

        @Volatile var closed = false

        override fun tokens(text: String) = text.split(' ').count { it.isNotEmpty() } + 1

        override fun translate(
            text: String,
            cancelled: () -> Boolean,
        ): String {
            onTranslate(cancelled)
            return "[$pair $text]"
        }

        override fun close() {
            closed = true
        }
    }

    private val catalog = OpusMtCatalog.load()
    private val opened = CopyOnWriteArrayList<FakeTranslator>()
    private var onTranslate: (cancelled: () -> Boolean) -> Unit = {}
    private val engine by lazy {
        OpusMtEngine(root, catalog, 5.minutes) { setup -> FakeTranslator(setup).also { opened += it } }
    }

    @AfterEach
    fun tearDown() {
        engine.close()
    }

    private fun model(
        pair: String,
        quality: Quality = Quality.MEDIUM,
    ) = pair.split('-').let { (source, target) -> PairModel(source, target, quality) }

    private fun language(code: String) = OpusMtLanguages.language(code)

    /** Puts every file of [model] in place with its catalog size (sparse, so the 200 MB ones cost nothing). */
    private fun install(model: PairModel) {
        for (file in engine.files(model)) {
            val path = engine.folder(model).resolve(file.path)
            Files.createDirectories(path.parent)
            RandomAccessFile(path.toFile(), "rw").use { it.setLength(file.bytes) }
        }
    }

    @ParameterizedTest
    @CsvSource(
        "de, fr, de-fr,",
        "de, sv, de-en, en-sv",
        "en, de, en-de,",
        "sv, en, sv-en,",
        "ja, ro, ja-en, en-ro",
    )
    fun `routes a pair directly when the catalog has it, else through English`(
        source: String,
        target: String,
        first: String,
        second: String?,
    ) {
        val expected = if (second == null) Route.Direct(first) else Route.ViaEnglish(first, second)
        assertEquals(expected, engine.route(language(source), language(target)))
    }

    @ParameterizedTest
    @CsvSource("de, de", "en, en", "en, ja", "ko, ja", "ro, de")
    fun `no route for the same language or a missing pair`(
        source: String,
        target: String,
    ) {
        assertNull(engine.route(language(source), language(target)))
    }

    @Test
    fun `offers the catalog's languages`() {
        assertEquals(OpusMtLanguages.sources(catalog), engine.sources)
        assertEquals(OpusMtLanguages.targets(catalog), engine.targets)
        assertTrue(Language("de", "German") in engine.sources)
    }

    @Test
    fun `the specs of a pair model`() {
        val model = model("de-en")
        assertEquals(root.resolve("de-en"), engine.folder(model))
        assertEquals(
            listOf(
                "tokenizer.json",
                "config.json",
                "generation_config.json",
                "medium/encoder.onnx",
                "medium/decoder.onnx",
            ),
            engine.files(model).map { it.path },
        )
        assertEquals(
            listOf(
                "high/encoder.onnx",
                "high/decoder.onnx",
            ),
            engine.files(model("de-en", Quality.HIGH)).drop(3).map {
                it.path
            },
        )
        assertEquals("Downloading translation model German → English (Medium) 40 %", engine.taskName(model, 40))
        assertEquals(
            "Downloading translation model English → Swedish (High) 5 %",
            engine.taskName(model("en-sv", Quality.HIGH), 5),
        )
    }

    @Test
    fun `installed means every file is there with its catalog size`() {
        val model = model("de-en")
        assertFalse(engine.installed(model))
        install(model)
        assertTrue(engine.installed(model))
        assertFalse(engine.installed(model("de-en", Quality.HIGH)))

        RandomAccessFile(engine.folder(model).resolve("config.json").toFile(), "rw").use { it.setLength(1) }
        assertFalse(engine.installed(model))
    }

    @Test
    fun `missing lists the route's models that are not installed, in route order`() {
        val route = Route.ViaEnglish("de-en", "en-sv")
        assertEquals(listOf(model("de-en"), model("en-sv")), engine.missing(route, Quality.MEDIUM))
        install(model("de-en"))
        assertEquals(listOf(model("en-sv")), engine.missing(route, Quality.MEDIUM))
        assertEquals(
            listOf(model("de-en", Quality.HIGH), model("en-sv", Quality.HIGH)),
            engine.missing(route, Quality.HIGH),
        )
        install(model("en-sv"))
        assertEquals(emptyList(), engine.missing(route, Quality.MEDIUM))
    }

    @Test
    fun `download sizes count what is missing, a pair's shared files once`() {
        val files = catalog.pairs.getValue("de-en")
        val shared = files.shared.sumOf { it.bytes }
        val medium = files.medium.sumOf { it.bytes }
        val high = files.high.sumOf { it.bytes }
        assertEquals(shared + medium + high, engine.bytes(listOf(model("de-en"), model("de-en", Quality.HIGH))))

        install(model("de-en"))
        assertEquals(0, engine.bytes(listOf(model("de-en"))))
        assertEquals(high, engine.bytes(listOf(model("de-en"), model("de-en", Quality.HIGH))))
        val sv = catalog.pairs.getValue("en-sv")
        assertEquals(
            sv.shared.sumOf {
                it.bytes
            } + sv.medium.sumOf { it.bytes },
            engine.bytes(listOf(model("de-en"), model("en-sv"))),
        )
    }

    @Test
    fun `a delete keeps the shared files while the other quality is installed or a download runs`() {
        val shared = setOf("tokenizer.json", "config.json", "generation_config.json")
        val medium = model("de-en")
        val high = model("de-en", Quality.HIGH)
        install(medium)
        assertEquals(emptySet(), engine.keptOnDelete(medium, downloadsRunning = false))
        assertEquals(shared, engine.keptOnDelete(medium, downloadsRunning = true))
        assertEquals(shared, engine.keptOnDelete(high, downloadsRunning = false))
        install(high)
        assertEquals(shared, engine.keptOnDelete(medium, downloadsRunning = false))
    }

    @Test
    fun `models lists every catalog pair in both qualities and language names fall back to the code`() {
        assertEquals(catalog.pairs.size * 2, engine.models.size)
        assertEquals(listOf(Quality.MEDIUM, Quality.HIGH), engine.models.take(2).map { it.quality })
        assertTrue(model("de-en") in engine.models && model("de-en", Quality.HIGH) in engine.models)
        assertEquals("German", engine.languageName("de"))
        assertEquals("xx", engine.languageName("xx"))
    }

    @Test
    fun `translating with a missing model fails readably without loading anything`() {
        install(model("de-en"))
        val error =
            assertFailsWith<TranslationException> {
                engine.translate("Hallo Welt.", Route.ViaEnglish("de-en", "en-sv"), Quality.MEDIUM) { false }
            }
        assertEquals("The translation model English → Swedish (Medium) is not installed.", error.message)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `a pivot route runs both models in order`() {
        install(model("de-en"))
        install(model("en-sv"))
        val route = Route.ViaEnglish("de-en", "en-sv")
        assertEquals("[en-sv [de-en Hallo Welt.]]", engine.translate("Hallo Welt.", route, Quality.MEDIUM) { false })
    }

    @Test
    fun `a blank sentence is returned as is`() {
        assertEquals("  ", engine.translate("  ", Route.Direct("de-en"), Quality.MEDIUM) { false })
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `a sentence too long for the model is translated in parts`() {
        install(model("de-en"))
        val half = List(250) { "Wort" }.joinToString(" ")
        val translated = engine.translate("$half, $half", Route.Direct("de-en"), Quality.MEDIUM) { false }
        assertEquals("[de-en $half,] [de-en $half]", translated)
    }

    @Test
    fun `cancelling before a model runs throws`() {
        install(model("de-en"))
        assertFailsWith<java.util.concurrent.CancellationException> {
            engine.translate("Hallo.", Route.Direct("de-en"), Quality.MEDIUM) { true }
        }
    }

    @Test
    fun `after close translations fail readably and the models are closed`() {
        install(model("de-en"))
        engine.translate("Hallo.", Route.Direct("de-en"), Quality.MEDIUM) { false }
        engine.close()
        assertTrue(opened.single().closed)
        val error =
            assertFailsWith<TranslationException> {
                engine.translate(
                    "Hallo.",
                    Route.Direct("de-en"),
                    Quality.MEDIUM,
                ) { false }
            }
        assertEquals("The translation engine is shutting down.", error.message)
    }

    @Test
    fun `closing during a translation stops the running decoder and fails the next model readably`() {
        install(model("de-en"))
        install(model("en-sv"))
        val decoderStopped = CopyOnWriteArrayList<Boolean>()
        onTranslate = { cancelled ->
            // The window's own cancel stays false: only the engine's close may stop the decoder.
            engine.close()
            decoderStopped += cancelled()
        }
        val error =
            assertFailsWith<TranslationException> {
                engine.translate("Hallo.", Route.ViaEnglish("de-en", "en-sv"), Quality.MEDIUM) { false }
            }
        assertEquals(listOf(true), decoderStopped)
        // The second model's session was refused (IllegalStateException), shown as shutting down.
        assertEquals("The translation engine is shutting down.", error.message)
        assertEquals(listOf("de-en"), opened.map { it.pair })
    }

    @Test
    fun `the multi-target models get their target-language token`() {
        for (pair in listOf("de-en", "en-zh", "en-ar", "en-vi")) install(model(pair))
        for (pair in listOf("de-en", "en-zh", "en-ar", "en-vi")) {
            engine.translate("Hallo.", Route.Direct(pair), Quality.MEDIUM) { false }
        }
        assertEquals(
            listOf("de-en" to null, "en-zh" to ">>cmn_Hans<<", "en-ar" to ">>ara<<", "en-vi" to ">>vie<<"),
            opened.map { it.pair to it.setup.targetToken },
        )
    }

    @Test
    fun `the encoder input starts with the target-language token's id`() {
        val tokenizer = fixtureTokenizer()
        assertContentEquals(intArrayOf(26, 4, 5, 6, 0), SourceEncoder(tokenizer, ">>xx<<", "x").encode("Hello world."))
        assertContentEquals(intArrayOf(4, 5, 6, 0), SourceEncoder(tokenizer, null, "x").encode("Hello world."))
    }

    @Test
    fun `a target-language token missing from the vocabulary fails readably`() {
        val error =
            assertFailsWith<TranslationException> {
                SourceEncoder(fixtureTokenizer(), ">>cmn_Hans<<", "English → Chinese (Medium)")
            }
        assertEquals(
            "The translation model English → Chinese (Medium) has no target-language token >>cmn_Hans<<.",
            error.message,
        )
    }
}
