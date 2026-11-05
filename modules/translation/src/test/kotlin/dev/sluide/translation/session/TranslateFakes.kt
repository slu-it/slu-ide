package dev.sluide.translation.session

import dev.sluide.localmodels.InstallState
import dev.sluide.translation.engine.Language
import dev.sluide.translation.engine.LanguageDetector
import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.Quality
import dev.sluide.translation.engine.Route
import dev.sluide.translation.engine.TranslationEngine
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

internal val german = Language("de", "German")
internal val english = Language("en", "English")
internal val swedish = Language("sv", "Swedish")
internal val finnish = Language("fi", "Finnish")

/**
 * Sources German, English, Swedish, Finnish; targets German, English, Swedish. Direct pairs to and from English,
 * German ↔ Swedish via English, Finnish has none. A translation is the sentence upper-cased. A model in [notInstalled]
 * is missing. [hold] keeps the translation of that sentence until [release] or until cancelled; [failures] throw.
 */
internal class FakeEngine : TranslationEngine {
    override val sources = listOf(german, english, swedish, finnish)
    override val targets = listOf(german, english, swedish)

    val notInstalled = mutableSetOf<PairModel>()

    /** The sentences given to [translate], in order. */
    val calls = CopyOnWriteArrayList<String>()
    val failures = mutableMapOf<String, Throwable>()

    @Volatile
    var hold: String? = null
    val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    /** Whether a held translation saw `cancelled()` turn true. */
    @Volatile
    var sawCancel = false

    fun release() = released.countDown()

    override fun route(
        source: Language,
        target: Language,
    ): Route? {
        if (source == target || source == finnish) return null
        return if (source == english || target == english) {
            Route.Direct("${source.code}-${target.code}")
        } else {
            Route.ViaEnglish("${source.code}-en", "en-${target.code}")
        }
    }

    override fun missing(
        route: Route,
        quality: Quality,
    ): List<PairModel> =
        route.pairs
            .map { PairModel(it.substringBefore('-'), it.substringAfter('-'), quality) }
            .filter { it in notInstalled }

    override fun translate(
        sentence: String,
        route: Route,
        quality: Quality,
        cancelled: () -> Boolean,
    ): String {
        calls += sentence
        failures[sentence]?.let { throw it }
        if (sentence == hold) {
            entered.countDown()
            while (!released.await(5, TimeUnit.MILLISECONDS)) {
                if (cancelled()) {
                    sawCancel = true
                    throw CancellationException()
                }
            }
        }
        return sentence.uppercase()
    }
}

/** Answers [answer] (or throws [error]) and counts its calls. */
internal class FakeDetector(
    @Volatile var answer: Language? = german,
) : LanguageDetector {
    @Volatile
    var calls = 0

    @Volatile
    var error: Exception? = null

    /** The text of the last detection. */
    @Volatile
    var lastText: String? = null

    override fun detect(
        text: String,
        among: Collection<Language>,
    ): Language? {
        calls++
        lastText = text
        error?.let { throw it }
        return answer
    }
}

/**
 * Every model installed unless [set] otherwise; 100 bytes per model still to fetch; records downloads and cancels and
 * runs [onDownload] for each model of a download.
 */
internal class FakeInstalls : ModelInstalls {
    var onDownload: (PairModel) -> Unit = {}

    private val states = mutableMapOf<PairModel, InstallState>()
    private val watchers = mutableListOf<Pair<Set<PairModel>, () -> Unit>>()
    val downloads = mutableListOf<List<PairModel>>()
    val cancels = mutableListOf<List<PairModel>>()

    val watching: Int get() = watchers.size

    fun set(
        model: PairModel,
        state: InstallState,
    ) {
        states[model] = state
        watchers.filter { model in it.first }.forEach { it.second() }
    }

    override fun state(model: PairModel) = states[model] ?: InstallState.Installed

    override fun missingBytes(models: List<PairModel>) = 100L * models.size

    override fun download(models: List<PairModel>) {
        downloads += models
        models.forEach(onDownload)
    }

    override fun cancel(models: List<PairModel>) {
        cancels += models
    }

    override fun watch(
        models: List<PairModel>,
        listener: () -> Unit,
    ): AutoCloseable {
        val watcher = models.toSet() to listener
        watchers += watcher
        return AutoCloseable { watchers.remove(watcher) }
    }
}

/** A scheduler the test steps; cancelled tasks never run; after [shutdown] it rejects tasks as an executor does. */
internal class ManualScheduler : TranslateScheduler {
    var shut = false
        private set

    class Task(
        val delay: Duration,
        val run: () -> Unit,
    ) {
        var cancelled = false
        var ran = false
    }

    val tasks = mutableListOf<Task>()

    val waiting: List<Task> get() = tasks.filter { !it.cancelled && !it.ran }

    override fun schedule(
        delay: Duration,
        task: () -> Unit,
    ): () -> Unit {
        if (shut) throw RejectedExecutionException("shut down")
        val scheduled = Task(delay, task)
        tasks += scheduled
        return { scheduled.cancelled = true }
    }

    override fun shutdown() {
        shut = true
    }

    /** Runs the one task that waits. */
    fun fire() {
        val task = waiting.single()
        task.ran = true
        task.run()
    }
}
