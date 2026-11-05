package dev.sluide.translation.session

import dev.sluide.textediting.Abbreviations
import dev.sluide.translation.engine.Language
import dev.sluide.translation.engine.LanguageDetector
import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.Quality
import dev.sluide.translation.engine.Route
import dev.sluide.translation.engine.TranslationEngine
import dev.sluide.translation.engine.TranslationException
import dev.sluide.translation.text.Segment
import dev.sluide.translation.text.TranslationText
import java.util.concurrent.CancellationException

private val log = System.getLogger(TranslationRun::class.java.name)

/** What one run translates, taken on the FX thread when it is started. */
internal data class TranslateRequest(
    val text: String,
    val markdown: Boolean,
    val source: SourceChoice,
    val target: Language,
    val quality: Quality,
)

/** What a run reports, in order; each but [Detecting] and [Progress] ends it. */
internal sealed interface RunEvent {
    data object Detecting : RunEvent

    data object Undetected : RunEvent

    data class Same(
        val source: Language,
    ) : RunEvent

    data class NoRoute(
        val source: Language,
    ) : RunEvent

    data class Missing(
        val source: Language,
        val route: Route,
        val models: List<PairModel>,
    ) : RunEvent

    /** [output] so far; [done] of [total] sentences that needed the engine are translated, [done] < [total]. */
    data class Progress(
        val source: Language,
        val output: String,
        val done: Int,
        val total: Int,
    ) : RunEvent

    data class Done(
        val source: Language,
        val output: String,
    ) : RunEvent

    /** The output keeps what the last [Progress] showed. */
    data class Failed(
        val source: Language?,
        val message: String,
    ) : RunEvent
}

/**
 * One translation of [request], run on the worker thread: detection, route, models, then sentence by sentence (the
 * [cache] first, the engine for the rest), reporting to [report]. Stops quietly once [cancelled] is true: before
 * every step and through the engine's own check between decoder steps.
 */
internal class TranslationRun(
    private val request: TranslateRequest,
    private val engine: TranslationEngine,
    private val detector: LanguageDetector,
    private val cache: SentenceCache,
    private val cancelled: () -> Boolean,
    private val report: (RunEvent) -> Unit,
) {
    private var source: Language? = null

    fun execute() {
        if (cancelled()) return
        try {
            translate()
        } catch (_: CancellationException) {
            // A newer run or the window's close stopped it.
        } catch (e: TranslationException) {
            if (!cancelled()) report(RunEvent.Failed(source, e.message ?: TranslateStatus.FAILED))
        } catch (e: Exception) {
            if (!cancelled()) {
                log.log(System.Logger.Level.ERROR, "Translation failed", e)
                report(RunEvent.Failed(source, TranslateStatus.FAILED))
            }
        } catch (e: Throwable) {
            // An Error: shown as a failure, rethrown for the worker thread's log.
            if (!cancelled()) report(RunEvent.Failed(source, TranslateStatus.FAILED))
            throw e
        }
    }

    private fun translate() {
        val source = source() ?: return
        this.source = source
        if (cancelled()) return
        if (source == request.target) return report(RunEvent.Same(source))
        val route = engine.route(source, request.target) ?: return report(RunEvent.NoRoute(source))
        val missing = engine.missing(route, request.quality)
        if (missing.isNotEmpty()) return report(RunEvent.Missing(source, route, missing))
        if (cancelled()) return
        val abbreviations = if (source.code == "de") Abbreviations.GERMAN else Abbreviations.ENGLISH
        val segments = TranslationText.segments(request.text, request.markdown, abbreviations)
        val sentences = segments.filterIsInstance<Segment.Sentence>()
        // This run's own copy: a cached sentence the LRU drops while the run goes on is still shown.
        val translated = HashMap<String, String>()
        for (sentence in sentences) {
            if (sentence.text !in translated) {
                cache.get(route.pairs, request.quality, sentence.text)?.let { translated[sentence.text] = it }
            }
        }
        val pending = sentences.map { it.text }.distinct().filter { it !in translated }

        fun output() = TranslationText.assemble(request.text, segments) { translated[it.text] }
        for ((done, sentence) in pending.withIndex()) {
            if (cancelled()) return
            report(RunEvent.Progress(source, output(), done, pending.size))
            val translation = engine.translate(sentence, route, request.quality, cancelled)
            cache.put(route.pairs, request.quality, sentence, translation)
            translated[sentence] = translation
        }
        if (cancelled()) return
        report(RunEvent.Done(source, output()))
    }

    /**
     * The sentences of the text, one per line, without what is kept verbatim (code, link targets, markup), which
     * would only blur the detection (placeholders such as `{1}` have no letters and do not count).
     */
    private fun detectionText(): String =
        TranslationText
            .segments(request.text, request.markdown, Abbreviations.ENGLISH)
            .filterIsInstance<Segment.Sentence>()
            .joinToString("\n") { it.text }

    private fun source(): Language? =
        when (val choice = request.source) {
            is SourceChoice.Picked -> {
                choice.language
            }

            SourceChoice.Detect -> {
                report(RunEvent.Detecting)
                val detected = detector.detect(detectionText(), engine.sources)
                if (detected == null && !cancelled()) report(RunEvent.Undetected)
                detected
            }
        }
}
