package dev.sluide.speech.kokoro

import ai.onnxruntime.OrtException
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.speech.engine.SetupProblem
import dev.sluide.speech.engine.SpeechChunk
import dev.sluide.speech.engine.SpeechEngine
import dev.sluide.speech.engine.SpeechException
import dev.sluide.speech.engine.SynthesizedChunk
import dev.sluide.speech.engine.Voice
import dev.sluide.speech.engine.WordTiming
import dev.sluide.speech.install.KokoroVariant
import dev.sluide.speech.install.ModelStore
import dev.sluide.speech.settings.SpeechSettings
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

private const val SAMPLE_RATE = 24_000
private const val IDLE_UNLOAD_MILLIS = 5L * 60 * 1000

/**
 * Speaks English with Kokoro-82M through ONNX Runtime (ADR 0124). One model session app-wide: [synthesize] calls are
 * serialized, the session loads on the first call (on the caller's thread), is replaced when the settings name another
 * variant, and is closed five minutes after the last call and in [destroy].
 *
 * Native calls pin virtual threads: callers use platform threads (the reading session does).
 */
@Component
class KokoroEngine internal constructor(
    private val settings: SpeechSettings,
    private val store: ModelStore,
    private val locator: EspeakLocator,
    private val tokenizer: KokoroTokenizer,
    private val voicesFactory: (Path) -> StyleSource,
    private val modelFactory: (Path) -> KokoroModel,
    private val scheduler: ScheduledExecutorService,
    private val idleMillis: Long,
) : SpeechEngine,
    DisposableBean {
    @Autowired
    constructor(
        settings: SpeechSettings,
        store: ModelStore,
    ) : this(settings, store, EspeakLocator(settings::currentEspeakPath))

    private constructor(settings: SpeechSettings, store: ModelStore, locator: EspeakLocator) : this(
        settings,
        store,
        locator,
        KokoroTokenizer(Espeak(locator::locate)::phonemes, KokoroVocab.load()),
        ::NpzVoices,
        ::KokoroSession,
        (NamedThreads.scheduled("kokoro-idle") as ScheduledThreadPoolExecutor).also { it.removeOnCancelPolicy = true },
        IDLE_UNLOAD_MILLIS,
    )

    private val lock = ReentrantLock()
    private var model: KokoroModel? = null
    private var loadedVariant: KokoroVariant? = null
    private var styles: StyleSource? = null
    private var idleTask: ScheduledFuture<*>? = null

    @Volatile private var destroyed = false

    init {
        // The property changes on the FX thread; invalidate() never blocks.
        settings.espeakPath.addListener { _, _, _ -> locator.invalidate() }
    }

    override val id: String = KokoroVoices.ENGINE_ID

    override val voices: List<Voice> = KokoroVoices.all

    override fun problems(): List<SetupProblem> {
        if (destroyed) return listOf(SetupProblem.Broken("The Kokoro engine is shutting down."))
        val problems = ArrayList<SetupProblem>()
        val variant = settings.currentVariant()
        if (!store.installed(variant)) {
            problems.add(SetupProblem.ModelMissing(id, variant.name, store.missingBytes(variant)))
        }
        if (locator.locate() == null) {
            locator.invalidate()
            problems.add(SetupProblem.ToolMissing("espeak-ng", ESPEAK_HINT))
        }
        return problems
    }

    @Throws(InterruptedException::class, SpeechException::class)
    override fun synthesize(
        chunk: SpeechChunk,
        voice: Voice,
        speed: Double,
    ): SynthesizedChunk {
        lock.lockInterruptibly()
        try {
            return synthesizeLocked(chunk, voice, speed.toFloat().coerceIn(0.5f, 2.0f))
        } finally {
            // destroy() may have run during the synthesis: it found no session to close yet, so close it here
            if (destroyed) unloadLocked() else scheduleUnload()
            lock.unlock()
        }
    }

    private fun synthesizeLocked(
        chunk: SpeechChunk,
        voice: Voice,
        speed: Float,
    ): SynthesizedChunk {
        if (destroyed) throw SpeechException("The Kokoro engine is shutting down.")
        val variant = settings.currentVariant()
        if (!store.installed(variant)) throw SpeechException("The Kokoro voice model is not installed.")
        if (locator.locate() == null) {
            locator.invalidate()
            throw SpeechException("espeak-ng not found. Install it or set its path in Settings → Speech.")
        }
        val pieces = tokenizer.split(tokenizer.tokenize(chunk, voice.language), KokoroTokenizer.MAX_TOKENS)
        val samples = ArrayList<FloatArray>()
        val words = ArrayList<WordTiming>()
        var total = 0
        for (piece in pieces) {
            if (Thread.interrupted()) throw InterruptedException()
            if (piece.ids.isEmpty()) {
                words.addAll(Timings.words(piece.words, FloatArray(2), 0, total))
                continue
            }
            val (waveform, durations) = runPiece(variant, piece, voice, speed)
            words.addAll(Timings.words(piece.words, durations, waveform.size, total))
            samples.add(waveform)
            total += waveform.size
        }
        val all = FloatArray(total)
        var at = 0
        for (part in samples) {
            part.copyInto(all, at)
            at += part.size
        }
        return SynthesizedChunk(chunk, all, SAMPLE_RATE, words)
    }

    private fun runPiece(
        variant: KokoroVariant,
        piece: TokenizedChunk,
        voice: Voice,
        speed: Float,
    ): Pair<FloatArray, FloatArray> {
        try {
            val loaded = ensureLoaded(variant)
            val style = styles?.style(voice.id, piece.ids.size) ?: throw SpeechException("Kokoro is not loaded.")
            return loaded.run(piece.ids, style, speed)
        } catch (e: OrtException) {
            throw SpeechException("Kokoro failed: ${e.message}", e)
        }
    }

    private fun ensureLoaded(variant: KokoroVariant): KokoroModel {
        model?.let { if (loadedVariant == variant) return it }
        unloadLocked()
        try {
            val loaded = modelFactory(store.modelFile(variant))
            val source =
                try {
                    voicesFactory(store.voicesFile())
                } catch (e: Throwable) {
                    loaded.close()
                    throw e
                }
            model = loaded
            loadedVariant = variant
            styles = source
            return loaded
        } catch (e: OrtException) {
            throw SpeechException("Kokoro failed: ${e.message}", e)
        } catch (e: LinkageError) {
            throw SpeechException("ONNX Runtime cannot be loaded on this system: ${e.message}", e)
        }
    }

    private fun unloadLocked() {
        val old = model
        model = null
        loadedVariant = null
        styles = null
        old?.close()
    }

    private fun scheduleUnload() {
        idleTask?.cancel(false)
        idleTask =
            try {
                scheduler.schedule(::unloadIfIdle, idleMillis, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                null
            }
    }

    private fun unloadIfIdle() {
        // A running synthesis holds the lock; it reschedules the unload when it ends.
        if (!lock.tryLock()) return
        try {
            unloadLocked()
        } finally {
            lock.unlock()
        }
    }

    override fun destroy() {
        destroyed = true
        scheduler.shutdownNow()
        lock.lock()
        try {
            unloadLocked()
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val ESPEAK_HINT =
            "Install it with `brew install espeak-ng` (macOS) or `sudo apt install espeak-ng` (Debian/Ubuntu), " +
                "or set its path in Settings → Speech."
    }
}
