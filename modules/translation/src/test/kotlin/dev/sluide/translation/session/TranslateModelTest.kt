package dev.sluide.translation.session

import dev.sluide.localmodels.InstallState
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.translation.MAX_READ_BYTES
import dev.sluide.translation.TranslationPreferences
import dev.sluide.translation.engine.Language
import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.Quality
import dev.sluide.translation.engine.TranslationException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The model with the worker and the dispatch run in place: every run ends within the call that starts it. */
class TranslateModelTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var store: ProjectDataStore
    private val engine = FakeEngine()
    private val detector = FakeDetector()
    private val installs = FakeInstalls()
    private val scheduler = ManualScheduler()
    private var runs = 0
    private val worker =
        Executor {
            runs++
            it.run()
        }
    private val statuses = mutableListOf<String>()
    private val outputs = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
    }

    private fun preferences() = TranslationPreferences(store, java.time.Duration.ZERO) { it.run() }

    private fun model(
        target: Language? = english,
        dispatch: (Runnable) -> Unit = Runnable::run,
        limit: Int = MAX_READ_BYTES,
    ): TranslateModel {
        val model =
            TranslateModel(engine, detector, installs, preferences(), dispatch, scheduler, worker, limit = limit)
        target?.let(model::chooseTarget)
        model.onChange = {
            if (statuses.lastOrNull() != model.status) statuses += model.status
            if (outputs.lastOrNull() != model.output) outputs += model.output
        }
        return model
    }

    @Test
    fun `without a target nothing is translated`() {
        val model = model(target = null)

        model.open("Eins. Zwei.", markdown = false)

        assertEquals(TranslateStatus.CHOOSE_TARGET, model.status)
        assertEquals("", model.output)
        assertFalse(model.running)
        assertEquals(0, runs)
        assertEquals(0, detector.calls)
        model.chooseTarget(english)
        assertEquals("EINS. ZWEI.", model.output)
    }

    @Test
    fun `a text over the limit is not translated, whatever changes, until it is short enough`() {
        val model = model(limit = 10)
        model.open("Eins.", markdown = false)
        assertEquals("EINS.", model.output)

        model.edit("Eins. Zwei. Drei.")
        assertEquals(TranslateStatus.TOO_LARGE, model.status)
        assertEquals("", model.output)
        assertFalse(model.running)
        assertEquals(0, scheduler.waiting.size)

        val before = engine.calls.size
        model.chooseTarget(swedish)
        model.chooseQuality(Quality.HIGH)
        model.chooseMarkdown(true)
        model.open("Eins. Zwei. Drei.", markdown = false)
        assertEquals(TranslateStatus.TOO_LARGE, model.status)
        assertEquals(before, engine.calls.size)

        model.edit("Zwei.")
        assertEquals("", model.status)
        scheduler.fire()
        assertEquals("ZWEI.", model.output)
        assertEquals(TranslateStatus.TRANSLATED, model.status)
    }

    @Test
    fun `an edit over the limit cancels the pending run`() {
        val model = model(limit = 10)
        model.edit("Eins.")
        assertEquals(1, scheduler.waiting.size)

        model.edit("Eins. Zwei. Drei.")

        assertEquals(0, scheduler.waiting.size)
        assertFalse(model.running)
    }

    @Test
    fun `typing translates once, a second after the last edit`() {
        val model = model()

        model.edit("E")
        model.edit("Ei")
        model.edit("Eins.")

        assertEquals(0, runs)
        assertTrue(model.running)
        assertEquals(1.seconds, scheduler.waiting.single().delay)
        scheduler.fire()
        assertEquals(1, runs)
        assertEquals(listOf("Eins."), engine.calls)
        assertEquals("EINS.", model.output)
        assertFalse(model.running)
    }

    @Test
    fun `text from outside and every choice translate at once`() {
        val model = model()
        model.edit("Eins.")

        model.open("Zwei.", markdown = false)
        assertEquals(1, runs)
        assertTrue(scheduler.waiting.isEmpty(), "the open replaces the pending edit")
        model.chooseQuality(Quality.HIGH)
        assertEquals(2, runs)
        model.chooseMarkdown(true)
        assertEquals(3, runs)
        model.chooseSource(SourceChoice.Picked(german))
        assertEquals(4, runs)
        model.chooseTarget(swedish)
        assertEquals(5, runs)
        assertTrue(scheduler.waiting.isEmpty())
        assertEquals("ZWEI.", model.output)
    }

    @Test
    fun `only edited sentences go to the engine, the others come from the cache`() {
        val model = model()
        model.open("Eins. Zwei. Drei.", markdown = false)
        engine.calls.clear()

        model.edit("Eins. Vier. Drei. Eins.")
        scheduler.fire()

        assertEquals(listOf("Vier."), engine.calls)
        assertEquals("EINS. VIER. DREI. EINS.", model.output)
        model.chooseQuality(Quality.HIGH)
        assertEquals(listOf("Vier.", "Eins.", "Vier.", "Drei."), engine.calls, "the quality is part of the key")
    }

    @Test
    fun `the status counts only the sentences the engine translates, the output grows after each`() {
        val model = model()
        model.open("Eins. Zwei.", markdown = false)
        model.edit("Eins. Drei. Zwei. Vier.")
        statuses.clear()
        outputs.clear()

        scheduler.fire()

        assertEquals(
            listOf(
                TranslateStatus.DETECTING,
                "Translating 1 of 2…",
                "Translating 2 of 2…",
                TranslateStatus.TRANSLATED,
            ),
            statuses,
        )
        // The old translation stays while detecting; then pending sentences contribute nothing, and the cached ones
        // are there before the first engine call.
        assertEquals(
            listOf("EINS. ZWEI.", "EINS.  ZWEI. ", "EINS. DREI. ZWEI. ", "EINS. DREI. ZWEI. VIER."),
            outputs,
        )
    }

    @Test
    fun `an undetected language clears the output and asks for the source`() {
        val model = model()
        model.open("Eins.", markdown = false)
        assertEquals(german, model.detected)

        detector.answer = null
        model.open("Hm.", markdown = false)

        assertEquals(TranslateStatus.NOT_DETECTED, model.status)
        assertEquals("", model.output)
        assertNull(model.detected)
        assertEquals(listOf("Eins."), engine.calls)
    }

    @Test
    fun `the same language shows the text unchanged`() {
        val model = model(target = german)

        model.open("# Titel\n\nEins. `code`", markdown = true)

        assertEquals("# Titel\n\nEins. `code`", model.output)
        assertEquals("Already German", model.status)
        assertTrue(engine.calls.isEmpty())
        model.chooseSource(SourceChoice.Picked(english))
        model.chooseTarget(english)
        assertEquals("Already English", model.status)
        assertNull(model.detected, "a picked source detects nothing")
    }

    @Test
    fun `a pair without a route says so`() {
        val model = model()
        model.chooseSource(SourceChoice.Picked(finnish))

        model.open("Yksi.", markdown = false)

        assertEquals("No translation from Finnish to English", model.status)
        assertEquals("", model.output)
        assertFalse(model.running)
    }

    @Test
    fun `missing models are a setup problem, and the translation starts once they are installed`() {
        val first = PairModel("de", "en", Quality.MEDIUM)
        val second = PairModel("en", "sv", Quality.MEDIUM)
        engine.notInstalled += listOf(first, second)
        installs.set(first, InstallState.NotInstalled)
        installs.set(second, InstallState.NotInstalled)
        val model = model(target = swedish)

        model.open("Eins.", markdown = false)

        val version = model.outputVersion
        val problem = model.problem
        assertEquals(
            SetupProblem(german, swedish, Quality.MEDIUM, listOf(first, second), true, 200, null, null),
            problem,
        )
        assertTrue(engine.calls.isEmpty())
        assertEquals("", model.output)
        assertFalse(model.running)
        model.download()
        model.cancelDownload()
        assertEquals(listOf(listOf(first, second)), installs.downloads)
        assertEquals(listOf(listOf(first, second)), installs.cancels)

        installs.set(first, InstallState.Downloading(0.5))
        assertEquals(0.25, model.problem?.progress)
        installs.set(first, InstallState.Installed)
        engine.notInstalled -= first
        assertEquals(100, model.problem?.bytes)
        assertNull(model.problem?.progress, "nothing downloads")
        installs.set(second, InstallState.Failed("Network down"))
        assertEquals("Network down", model.problem?.failure)
        assertTrue(engine.calls.isEmpty())
        assertEquals(version, model.outputVersion, "download ticks leave the output alone")

        engine.notInstalled -= second
        installs.set(second, InstallState.Installed)

        assertNull(model.problem)
        assertEquals("EINS.", model.output)
        assertEquals(TranslateStatus.TRANSLATED, model.status)
        assertEquals(0, installs.watching)
    }

    @Test
    fun `a problem shown while the models are already installed recovers with Download`() {
        // Installed between the engine's disk check and the watch: no state change would ever come by itself.
        val pair = PairModel("de", "en", Quality.MEDIUM)
        engine.notInstalled += pair
        val model = model()
        model.open("Eins.", markdown = false)
        assertEquals(listOf(pair), model.problem?.missing)
        engine.notInstalled -= pair
        installs.onDownload = {
            installs.set(it, InstallState.Downloading(0.0))
            installs.set(it, InstallState.Installed)
        }

        model.download()

        assertNull(model.problem)
        assertEquals("EINS.", model.output)
    }

    @Test
    fun `a slow FX thread gets only the latest progress, and the output version follows real changes only`() {
        val queued = ArrayDeque<Runnable>()
        val model = model(dispatch = queued::add)

        model.open("Eins. Zwei. Drei. Vier.", markdown = false)

        // Detecting, one progress (the latest of four), done.
        assertEquals(3, queued.size)
        val applied = mutableListOf<String>()
        val versions = mutableListOf<Int>()
        model.onChange = {
            applied += model.status
            versions += model.outputVersion
        }
        while (queued.isNotEmpty()) queued.removeFirst().run()
        assertEquals(listOf(TranslateStatus.DETECTING, "Translating 4 of 4…", TranslateStatus.TRANSLATED), applied)
        assertEquals(listOf(0, 1, 2), versions, "detecting keeps the output")
    }

    @Test
    fun `a failure before any progress clears the output`() {
        val model = model()
        model.open("Eins.", markdown = false)
        detector.error = IllegalStateException("bug")

        model.open("Zwei.", markdown = false)

        assertEquals(TranslateStatus.FAILED, model.status)
        assertEquals("", model.output)
    }

    @ParameterizedTest
    @CsvSource("true, Model file broken", "false, Translation failed")
    fun `a failure shows its message and keeps what was translated`(
        shown: Boolean,
        status: String,
    ) {
        engine.failures["Zwei."] =
            if (shown) TranslationException("Model file broken") else IllegalStateException("bug")
        val model = model()

        model.open("Eins. Zwei. Drei.", markdown = false)

        assertEquals(status, model.status)
        assertEquals("EINS.  ", model.output)
        assertFalse(model.running)
        assertEquals(listOf("Eins.", "Zwei."), engine.calls)
    }

    @Test
    fun `German text is split with the German abbreviations`() {
        val model = model(target = swedish)

        model.open("Wir treffen uns ca. Montag. Gut.", markdown = false)
        model.chooseSource(SourceChoice.Picked(english))

        assertEquals(
            listOf("Wir treffen uns ca. Montag.", "Gut.", "Wir treffen uns ca.", "Montag.", "Gut."),
            engine.calls,
        )
    }

    @Test
    fun `the choices are saved and read back by the next window`() {
        val model = model(target = swedish)

        model.chooseSource(SourceChoice.Picked(german))
        model.chooseQuality(Quality.HIGH)

        val next = model(target = null)
        assertEquals(SourceChoice.Picked(german), next.source)
        assertEquals(swedish, next.target)
        assertEquals(Quality.HIGH, next.quality)
        assertEquals("", next.status)
        next.chooseSource(SourceChoice.Detect)
        assertEquals(SourceChoice.Detect, model(target = null).source)
    }

    @Test
    fun `saved codes the engine does not offer fall back`() {
        // Finnish is a source but no target.
        store.updateShared { root ->
            root.set("translation", JsonMapper().readTree("""{"source":"xx","target":"fi","quality":"low"}"""))
        }

        val model = model(target = null)

        assertEquals(SourceChoice.Detect, model.source)
        assertNull(model.target)
        assertEquals(Quality.MEDIUM, model.quality)
        assertEquals(TranslateStatus.CHOOSE_TARGET, model.status)
    }

    @Test
    fun `a disposed model ignores later calls and stops its timer`() {
        val model = model()
        model.edit("Eins.")

        model.dispose()
        model.open("Zwei.", markdown = false)

        assertTrue(scheduler.shut)
        assertTrue(scheduler.waiting.isEmpty())
        assertEquals(0, runs)
        assertEquals("", model.output)
    }
}
