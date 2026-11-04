package dev.sluide.speech.engine

/** The gender a voice is published with; the voice picker shows it in the label. */
enum class Gender { FEMALE, MALE }

/**
 * A voice of one [SpeechEngine], known without loading the engine's model.
 *
 * @property id the engine's own voice id (`af_heart`), unique within the engine.
 * @property name the name shown to the user (`Heart`).
 * @property language the BCP 47 tag of the spoken language (`en-US`, `en-GB`); the picker groups by it.
 * @property grade the quality grade the engine's publisher gives the voice (`A` best, then `B-` and lower), or null
 *   when the engine has no grades; the picker lists the best grade first.
 * @property engineId the [SpeechEngine.id] that speaks this voice.
 */
data class Voice(
    val id: String,
    val name: String,
    val language: String,
    val gender: Gender,
    val grade: String?,
    val engineId: String,
)

/**
 * A piece of text synthesized in one go. All offsets are in `SpokenText.text`, ends exclusive.
 *
 * [text] is `spoken.text.substring(start, end)`. [sentenceStart] to [sentenceEnd] is the range highlighted as the
 * sentence: usually the chunk's own range, but a split first sentence gives two chunks with the same sentence range.
 */
data class SpeechChunk(
    val start: Int,
    val end: Int,
    val sentenceStart: Int,
    val sentenceEnd: Int,
    val text: String,
)

/**
 * When a word of a chunk is spoken: [start] to [end] (exclusive) are `SpokenText` offsets, [startSample] to
 * [endSample] (exclusive) are sample indexes in [SynthesizedChunk.samples].
 */
data class WordTiming(
    val start: Int,
    val end: Int,
    val startSample: Int,
    val endSample: Int,
)

/** The audio of one [chunk]: mono [samples] in -1..1 at [sampleRate] Hz, and when each word of it is spoken. */
class SynthesizedChunk(
    val chunk: SpeechChunk,
    val samples: FloatArray,
    val sampleRate: Int,
    val words: List<WordTiming>,
)

/** Why an engine cannot speak yet; [SpeechEngine.problems] lists them, the tab shows them as the setup notice. */
sealed interface SetupProblem {
    /** The model [variant] of engine [engineId] is not downloaded; [bytes] is its download size. */
    data class ModelMissing(
        val engineId: String,
        val variant: String,
        val bytes: Long,
    ) : SetupProblem

    /** An external [tool] (`espeak-ng`) is not found; [hint] tells how to install it or where to set its path. */
    data class ToolMissing(
        val tool: String,
        val hint: String,
    ) : SetupProblem

    /** Something is there but does not work (a damaged model, a tool that fails); [message] is shown as is. */
    data class Broken(
        val message: String,
    ) : SetupProblem
}

/** A setup or synthesis failure of a [SpeechEngine]; [message] is readable for the user and shown as is. */
class SpeechException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Turns text into speech for the languages of its [voices]. One implementation per engine and model (Kokoro for
 * English; later Piper for German or a server), so the reading session stays engine-neutral (ADR 0124).
 *
 * Implementations are thread-safe: [problems] and [synthesize] may be called concurrently from several threads
 * (several windows read at once). An implementation may serialize the calls internally.
 */
interface SpeechEngine {
    /** Unique among the engines; [Voice.engineId] refers to it. */
    val id: String

    /** The engine's voices; static, available without the model. */
    val voices: List<Voice>

    /** What keeps the engine from speaking; empty when it is ready. Blocking: call it off the FX thread. */
    fun problems(): List<SetupProblem>

    /**
     * Synthesizes [chunk] with [voice] at [speed] (1.0 = normal). Blocking: call it off the FX thread. Splits the
     * chunk internally when it exceeds the model's limit.
     *
     * Throws [SpeechException] for setup or synthesis failures, with a message for the user, and
     * [InterruptedException] when the calling thread is interrupted between steps. Any other exception is a bug, but
     * callers treat it as a failure too.
     */
    @Throws(InterruptedException::class, SpeechException::class)
    fun synthesize(
        chunk: SpeechChunk,
        voice: Voice,
        speed: Double,
    ): SynthesizedChunk
}
