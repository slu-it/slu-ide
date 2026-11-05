package dev.sluide.translation.session

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.localmodels.InstallState
import dev.sluide.translation.MAX_READ_BYTES
import dev.sluide.translation.TranslationPreferences
import dev.sluide.translation.engine.Language
import dev.sluide.translation.engine.LanguageDetector
import dev.sluide.translation.engine.Quality
import dev.sluide.translation.engine.Route
import dev.sluide.translation.engine.TranslationEngine
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The state behind one Translate tab (ADR 0126, ADR 0129), without the toolkit (ADR 0084): the text, the languages,
 * quality and Markdown toggle, and what the translation shows; the sentence cache is the tab's own. Every public call
 * and [onChange] belong to the FX thread. A run (detection, route, sentences) goes to [worker], a typing edit's after
 * [delay]; its events come back through [dispatch] (the FX thread in the app), and only the latest run's count. A new
 * run cancels the one before:
 * the engine sees `cancelled()` between decoder steps. A run's progress is coalesced: at most one progress event waits
 * in [dispatch] at a time, and it applies the latest one, so a slow FX thread never queues stale copies of the output.
 *
 * [onChange] fires after every change, also for status-only changes (a download's progress, "Translating 3 of 40…").
 * The view replaces the translation's text only when [outputVersion] changed, so the caret and selection on the
 * read-only side survive the other changes.
 *
 * [worker] is this tab's own platform thread by default (native ONNX calls); the engine serializes the calls per
 * model, so tabs and windows translate side by side. [dispose] cancels the run and the timer; the idle worker thread
 * then ends by itself. Source, target and quality are read from [preferences] at creation and saved on every change; a saved
 * code the engine does not offer falls back to Detect or no target. The text is never stored.
 *
 * A text longer than [limit] chars is kept but not translated: the run and the timer are cancelled, the output is
 * cleared and the status says so, also after a change of target, quality or Markdown, until the text is short enough.
 */
internal class TranslateModel(
    private val engine: TranslationEngine,
    private val detector: LanguageDetector,
    private val installs: ModelInstalls,
    private val preferences: TranslationPreferences,
    private val dispatch: (Runnable) -> Unit,
    private val scheduler: TranslateScheduler = TranslateScheduler.singleThread(),
    private val worker: Executor = NamedThreads.single("sluide-translate"),
    private val delay: Duration = 1.seconds,
    private val cache: SentenceCache = SentenceCache(),
    private val limit: Int = MAX_READ_BYTES,
) {
    /** A started run; [cancelled] is read by the worker. */
    private class Run {
        @Volatile
        var cancelled = false

        /** The latest progress not applied yet; a dispatch is pending while it is set. */
        val progress = AtomicReference<RunEvent.Progress?>()

        /** Whether a progress of this run was applied (FX thread); a failure before one clears the output. */
        var progressed = false
    }

    val sources: List<Language> = engine.sources
    val targets: List<Language> = engine.targets

    var text: String = ""
        private set
    var markdown: Boolean = false
        private set
    var source: SourceChoice =
        preferences.source?.let { code -> sources.firstOrNull { it.code == code } }?.let(SourceChoice::Picked)
            ?: SourceChoice.Detect
        private set

    /** The language detected in the text while [source] is Detect; null before a detection or when none was found. */
    var detected: Language? = null
        private set

    /** Null until one is chosen ("Choose target…"): nothing is translated then. */
    var target: Language? = preferences.target?.let { code -> targets.firstOrNull { it.code == code } }
        private set
    var quality: Quality = preferences.quality
        private set

    /** Counts the changes of [output]: the view sets the translation's text only when it changed. */
    var outputVersion: Int = 0
        private set

    var output: String = ""
        private set(value) {
            if (value != field) {
                field = value
                outputVersion++
            }
        }
    var status: String = if (target == null) TranslateStatus.CHOOSE_TARGET else ""
        private set

    /** Set while the models of the current route are missing; nothing is translated then. */
    var problem: SetupProblem? = null
        private set

    /** Whether a translation waits for its debounce or runs. */
    var running: Boolean = false
        private set

    /** Called once after every state change, on the FX thread. */
    var onChange: () -> Unit = {}

    private var run: Run? = null
    private var cancelTimer: () -> Unit = {}
    private var watch: AutoCloseable? = null
    private var disposed = false

    /** The text was typed: it is translated [delay] after the last edit. */
    fun edit(text: String) {
        // The view sets the text the model gave it; that is no edit.
        if (disposed || text == this.text) return
        this.text = text
        retranslate(delay)
        onChange()
    }

    /** Text from an editor or the explorer replaces the text and is translated at once. */
    fun open(
        text: String,
        markdown: Boolean,
    ) {
        if (disposed) return
        this.text = text
        this.markdown = markdown
        retranslate()
        onChange()
    }

    fun chooseSource(source: SourceChoice) {
        if (disposed || source == this.source) return
        this.source = source
        detected = null
        changed()
    }

    fun chooseTarget(target: Language) {
        if (disposed || target == this.target) return
        this.target = target
        changed()
    }

    fun chooseQuality(quality: Quality) {
        if (disposed || quality == this.quality) return
        this.quality = quality
        changed()
    }

    fun chooseMarkdown(markdown: Boolean) {
        if (disposed || markdown == this.markdown) return
        this.markdown = markdown
        retranslate()
        onChange()
    }

    /** Downloads the models the [problem] names; the translation starts by itself once they are all installed. */
    fun download() {
        val problem = problem ?: return
        installs.download(problem.missing)
    }

    fun cancelDownload() {
        val problem = problem ?: return
        installs.cancel(problem.missing)
    }

    /** The English name of the language with Opus-MT [code] (`de` → "German"), for the setup notice; else the code. */
    fun languageName(code: String): String = (sources + targets).firstOrNull { it.code == code }?.name ?: code

    /**
     * Cancels the run and the timer and drops results still to come; later calls do nothing. Closing the tab calls it
     * (`Translate.close` for the tabs still open when the window scope ends): until then the timer thread and the
     * installer's listeners keep the model alive.
     */
    fun dispose() {
        disposed = true
        cancelRun()
        clearProblem()
        scheduler.shutdown()
    }

    private fun changed() {
        preferences.save((source as? SourceChoice.Picked)?.language?.code, target?.code, quality)
        retranslate()
        onChange()
    }

    private fun cancelRun() {
        cancelTimer()
        cancelTimer = {}
        run?.cancelled = true
        run = null
    }

    /** Starts a run of the current state after [delay]; without a target or text, shows why nothing runs. */
    private fun retranslate(delay: Duration = Duration.ZERO) {
        cancelRun()
        val target = target
        if (text.length > limit) {
            clearProblem()
            running = false
            output = ""
            detected = null
            status = TranslateStatus.TOO_LARGE
            return
        }
        if (target == null || text.isBlank()) {
            clearProblem()
            running = false
            output = ""
            detected = null
            status = if (target == null) TranslateStatus.CHOOSE_TARGET else ""
            return
        }
        if (status == TranslateStatus.TOO_LARGE) status = ""
        val run = Run()
        this.run = run
        running = true
        val request = TranslateRequest(text, markdown, source, target, quality)
        val job =
            TranslationRun(request, engine, detector, cache, { run.cancelled }) { event ->
                if (event !is RunEvent.Progress) {
                    dispatch(Runnable { apply(run, request, event) })
                } else if (run.progress.getAndSet(event) == null) {
                    // FIFO dispatch: this runnable runs before the run's later events and applies the latest progress.
                    dispatch(Runnable { run.progress.getAndSet(null)?.let { apply(run, request, it) } })
                }
            }
        if (delay == Duration.ZERO) {
            worker.execute(job::execute)
        } else {
            cancelTimer = scheduler.schedule(delay) { worker.execute(job::execute) }
        }
    }

    private fun apply(
        run: Run,
        request: TranslateRequest,
        event: RunEvent,
    ) {
        if (disposed || run !== this.run) return
        when (event) {
            RunEvent.Detecting -> {
                status = TranslateStatus.DETECTING
            }

            RunEvent.Undetected -> {
                ended(null)
                output = ""
                status = TranslateStatus.NOT_DETECTED
            }

            is RunEvent.Same -> {
                ended(event.source)
                output = request.text
                status = TranslateStatus.already(event.source)
            }

            is RunEvent.NoRoute -> {
                ended(event.source)
                output = ""
                status = TranslateStatus.noRoute(event.source, request.target)
            }

            is RunEvent.Missing -> {
                ended(event.source)
                output = ""
                status = ""
                showProblem(request, event)
            }

            is RunEvent.Progress -> {
                run.progressed = true
                detectedAs(event.source)
                clearProblem()
                output = event.output
                status = TranslateStatus.translating(event.done + 1, event.total)
            }

            is RunEvent.Done -> {
                ended(event.source)
                output = event.output
                status = TranslateStatus.TRANSLATED
            }

            is RunEvent.Failed -> {
                ended(event.source)
                if (!run.progressed) output = ""
                status = event.message
            }
        }
        onChange()
    }

    private fun ended(source: Language?) {
        detectedAs(source)
        clearProblem()
        running = false
        run = null
    }

    private fun detectedAs(language: Language?) {
        if (source == SourceChoice.Detect) detected = language
    }

    private fun showProblem(
        request: TranslateRequest,
        event: RunEvent.Missing,
    ) {
        val base =
            SetupProblem(
                source = event.source,
                target = request.target,
                quality = request.quality,
                missing = event.models,
                viaEnglish = event.route is Route.ViaEnglish,
                bytes = 0,
                progress = null,
                failure = null,
            )
        problem = refreshed(base)
        watch = installs.watch(event.models) { installsChanged() }
    }

    /** A missing model's state changed: the notice follows; once all are installed the translation starts. */
    private fun installsChanged() {
        val problem = problem ?: return
        if (disposed) return
        if (problem.missing.all { installs.state(it) == InstallState.Installed }) {
            clearProblem()
            retranslate()
        } else {
            this.problem = refreshed(problem)
        }
        onChange()
    }

    private fun refreshed(problem: SetupProblem): SetupProblem {
        val states = problem.missing.map(installs::state)
        val downloading = states.any { it is InstallState.Downloading }
        val progress =
            states.sumOf {
                when (it) {
                    is InstallState.Downloading -> it.progress
                    InstallState.Installed -> 1.0
                    else -> 0.0
                }
            } / states.size
        val notInstalled = problem.missing.filterIndexed { i, _ -> states[i] != InstallState.Installed }
        return problem.copy(
            bytes = installs.missingBytes(notInstalled),
            progress = progress.takeIf { downloading },
            failure = states.firstNotNullOfOrNull { (it as? InstallState.Failed)?.message },
        )
    }

    private fun clearProblem() {
        watch?.close()
        watch = null
        problem = null
    }
}
