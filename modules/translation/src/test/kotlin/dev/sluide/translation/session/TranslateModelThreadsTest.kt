package dev.sluide.translation.session

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.translation.TranslationPreferences
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The model with a real worker thread; the test thread plays the FX thread and runs what the worker dispatches. The
 * engine holds a sentence until it is cancelled.
 */
class TranslateModelThreadsTest {
    @TempDir
    lateinit var dir: Path

    private val engine = FakeEngine()
    private val scheduler = ManualScheduler()
    private val worker = NamedThreads.single("sluide-translate-test")
    private val dispatched = LinkedBlockingQueue<Runnable>()
    private val outputs = mutableListOf<String>()

    @AfterEach
    fun tearDown() {
        engine.release()
        worker.shutdownNow()
        worker.awaitTermination(10, TimeUnit.SECONDS)
    }

    private fun model(): TranslateModel {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = TranslationPreferences(store, java.time.Duration.ZERO) { it.run() }
        val model =
            TranslateModel(engine, FakeDetector(), FakeInstalls(), preferences, dispatched::add, scheduler, worker)
        model.chooseTarget(english)
        model.onChange = { outputs += model.output }
        return model
    }

    /** Runs dispatched events on this thread until [done] holds. */
    private fun pumpUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            if (System.nanoTime() > deadline) fail("timed out")
            dispatched.poll(10, TimeUnit.MILLISECONDS)?.run()
        }
    }

    /** Waits until the worker has run everything queued so far, then runs what it dispatched. */
    private fun settle() {
        worker.submit {}.get(10, TimeUnit.SECONDS)
        while (true) dispatched.poll()?.run() ?: break
    }

    @Test
    fun `a new run cancels the running one, whose results never show`() {
        engine.hold = "Zwei."
        val model = model()
        model.open("Eins. Zwei.", markdown = false)
        assertTrue(engine.entered.await(10, TimeUnit.SECONDS))

        // The first run's events (its progress with "EINS.") are still queued for the FX thread.
        model.open("Drei.", markdown = false)
        pumpUntil { model.status == TranslateStatus.TRANSLATED }
        settle()

        assertTrue(engine.sawCancel)
        assertEquals("DREI.", model.output)
        assertTrue(outputs.none { "EINS" in it }, "stale output: $outputs")
        assertEquals(listOf("Eins.", "Zwei.", "Drei."), engine.calls)
    }

    @Test
    fun `dispose stops a running translation and drops its results`() {
        engine.hold = "Zwei."
        val model = model()
        model.open("Eins. Zwei.", markdown = false)
        assertTrue(engine.entered.await(10, TimeUnit.SECONDS))

        model.dispose()
        pumpUntil { engine.sawCancel }
        settle()

        assertTrue(scheduler.shut)
        assertEquals("", model.output)
        assertFalse(model.status == TranslateStatus.TRANSLATED)
        assertEquals(listOf("Eins.", "Zwei."), engine.calls)
    }
}
