package dev.sluide.speech.kokoro

import ai.onnxruntime.OrtException
import dev.sluide.localmodels.IdleSession
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private const val SAMPLE_RATE = 24_000
private val IDLE_UNLOAD = 5.minutes
private const val SHUTTING_DOWN = "The Kokoro engine is shutting down."

/**
 * Speaks English with Kokoro-82M through ONNX Runtime (ADR 0124). One model session app-wide, an [IdleSession]
 * (ADR 0125): [synthesize] calls are serialized, the session loads on the first call (on the caller's thread), is
 * replaced when the settings name another variant, and is closed five minutes after the last call and in [destroy].
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
    idle: Duration,
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
        IDLE_UNLOAD,
    )

    private val session = IdleSession("kokoro-idle", idle) { LoadedKokoro() }

    @Volatile private var destroyed = false

    init {
        // The property changes on the FX thread; invalidate() never blocks.
        settings.espeakPath.addListener { _, _, _ -> locator.invalidate() }
    }

    override val id: String = KokoroVoices.ENGINE_ID

    override val voices: List<Voice> = KokoroVoices.all

    override fun problems(): List<SetupProblem> {
        if (destroyed) return listOf(SetupProblem.Broken(SHUTTING_DOWN))
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
        if (destroyed) throw SpeechException(SHUTTING_DOWN)
        try {
            return session.use { loaded ->
                synthesizeLocked(loaded, chunk, voice, speed.toFloat().coerceIn(0.5f, 2.0f))
            }
        } catch (e: IllegalStateException) {
            // destroy() closed the session between the check above and the use
            if (destroyed) throw SpeechException(SHUTTING_DOWN, e)
            throw e
        }
    }

    private fun synthesizeLocked(
        loaded: LoadedKokoro,
        chunk: SpeechChunk,
        voice: Voice,
        speed: Float,
    ): SynthesizedChunk {
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
            val (waveform, durations) = runPiece(loaded, variant, piece, voice, speed)
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
        loaded: LoadedKokoro,
        variant: KokoroVariant,
        piece: TokenizedChunk,
        voice: Voice,
        speed: Float,
    ): Pair<FloatArray, FloatArray> {
        try {
            val (model, styles) = loaded.ensure(variant)
            return model.run(piece.ids, styles.style(voice.id, piece.ids.size), speed)
        } catch (e: OrtException) {
            throw SpeechException("Kokoro failed: ${e.message}", e)
        }
    }

    override fun destroy() {
        destroyed = true
        session.close()
    }

    /**
     * The model and voices of the variant last asked for; another variant replaces them. The [session] creates it
     * empty and closes it when idle; it is used under the session's lock only.
     */
    private inner class LoadedKokoro : AutoCloseable {
        private var model: KokoroModel? = null
        private var styles: StyleSource? = null
        private var variant: KokoroVariant? = null

        fun ensure(wanted: KokoroVariant): Pair<KokoroModel, StyleSource> {
            val current = model
            val currentStyles = styles
            if (current != null && currentStyles != null && variant == wanted) return current to currentStyles
            close()
            try {
                val loaded = modelFactory(store.modelFile(wanted))
                val source =
                    try {
                        voicesFactory(store.voicesFile())
                    } catch (e: Throwable) {
                        loaded.close()
                        throw e
                    }
                model = loaded
                styles = source
                variant = wanted
                return loaded to source
            } catch (e: OrtException) {
                throw SpeechException("Kokoro failed: ${e.message}", e)
            } catch (e: LinkageError) {
                throw SpeechException("ONNX Runtime cannot be loaded on this system: ${e.message}", e)
            }
        }

        override fun close() {
            val old = model
            model = null
            styles = null
            variant = null
            old?.close()
        }
    }

    private companion object {
        const val ESPEAK_HINT =
            "Install it with `brew install espeak-ng` (macOS) or `sudo apt install espeak-ng` (Debian/Ubuntu), " +
                "or set its path in Settings → Speech."
    }
}
