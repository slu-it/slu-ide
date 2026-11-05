package dev.sluide.translation.opusmt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import dev.sluide.translation.engine.TranslationException
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Path

/** Where the files of one Opus-MT pair model are (the layout of the catalog, ADR 0126). */
internal data class MarianFiles(
    val tokenizer: Path,
    val config: Path,
    val generationConfig: Path,
    val encoder: Path,
    val decoder: Path,
)

/**
 * A loaded Opus-MT model (CPU): its tokenizer, configuration, and the encoder and optimum's merged decoder as ONNX
 * Runtime sessions. Not thread-safe; the engine serializes the calls. Loading and running block and call native code:
 * use platform threads. Every problem with the files or the graphs is a [TranslationException] naming the model
 * ([label], "German → English (Medium)").
 *
 * Expected graphs (optimum / Xenova exports; the names are checked on load): the encoder takes `input_ids` and
 * `attention_mask` (int64 `[1, S]`) and gives `last_hidden_state`; the decoder takes `input_ids`,
 * `encoder_attention_mask`, `encoder_hidden_states`, `use_cache_branch` (bool `[1]`) and
 * `past_key_values.<i>.decoder|encoder.key|value` (`[B, heads, T, headDim]`) and gives `logits` and
 * `present.<i>.decoder|encoder.key|value`.
 */
internal class MarianSession(
    files: MarianFiles,
    private val label: String,
) : AutoCloseable {
    val tokenizer: UnigramTokenizer
    val config: MarianConfig
    private val env = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val encoderMaskName: String?
    private val decoderMaskName: String?

    init {
        try {
            tokenizer = UnigramTokenizer.load(files.tokenizer)
            config = MarianConfig.load(files.config, files.generationConfig)
        } catch (e: Exception) {
            throw TranslationException("Cannot read the translation model $label: ${e.message}", e)
        }
        encoder = open(files.encoder)
        try {
            decoder = open(files.decoder)
            try {
                encoderMaskName = checkEncoder()
                decoderMaskName = checkDecoder()
            } catch (e: Throwable) {
                decoder.close()
                throw e
            }
        } catch (e: Throwable) {
            encoder.close()
            throw e
        }
    }

    /**
     * The target ids for the source [ids] (with `</s>`), `</s>` included when the search ended there. Polls
     * [cancelled] before every decoder step and throws [java.util.concurrent.CancellationException].
     */
    fun translate(
        ids: IntArray,
        cancelled: () -> Boolean,
    ): IntArray {
        try {
            val (hidden, width) = encode(ids)
            return Decoding(ids.size, hidden, width).use { decoding ->
                BeamSearch(config).search(config.decoderStartId, decoding, cancelled)
            }
        } catch (e: OrtException) {
            throw TranslationException("The translation model $label failed: ${e.message}", e)
        }
    }

    override fun close() {
        try {
            decoder.close()
        } finally {
            encoder.close()
        }
    }

    private fun open(file: Path): OrtSession =
        try {
            OrtSession.SessionOptions().use { options ->
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                env.createSession(file.toString(), options)
            }
        } catch (e: OrtException) {
            throw TranslationException("Cannot load the translation model $label: ${e.message}", e)
        }

    private fun checkEncoder(): String? {
        if (INPUT_IDS !in encoder.inputNames) unsupported("the encoder has no $INPUT_IDS input")
        if (HIDDEN_STATE !in encoder.outputNames) unsupported("the encoder has no $HIDDEN_STATE output")
        return ATTENTION_MASK.takeIf { it in encoder.inputNames }
    }

    private fun checkDecoder(): String? {
        val inputs = decoder.inputNames
        val outputs = decoder.outputNames
        val needed = listOf(INPUT_IDS, ENCODER_HIDDEN_STATES, USE_CACHE) + pastNames("past_key_values")
        needed.firstOrNull { it !in inputs }?.let { unsupported("the decoder has no $it input") }
        (listOf(LOGITS) + pastNames("present")).firstOrNull { it !in outputs }?.let {
            unsupported("the decoder has no $it output")
        }
        return ENCODER_ATTENTION_MASK.takeIf { it in inputs }
    }

    private fun unsupported(what: String): Nothing =
        throw TranslationException("Unsupported translation model $label: $what")

    /** `<prefix>.<i>.decoder.key`, `.value`, `.encoder.key`, `.value` per layer, in that order. */
    private fun pastNames(prefix: String): List<String> =
        (0 until config.decoderLayers).flatMap { layer ->
            listOf("decoder.key", "decoder.value", "encoder.key", "encoder.value").map { "$prefix.$layer.$it" }
        }

    /** The encoder's last hidden state of [ids], `[S * width]`, and its width. */
    private fun encode(ids: IntArray): Pair<FloatArray, Int> {
        val shape = longArrayOf(1, ids.size.toLong())
        val tensors = ArrayList<OnnxTensor>()
        try {
            val inputs = HashMap<String, OnnxTensor>()
            inputs[INPUT_IDS] = longTensor(LongArray(ids.size) { ids[it].toLong() }, shape).also(tensors::add)
            encoderMaskName?.let { inputs[it] = longTensor(LongArray(ids.size) { 1 }, shape).also(tensors::add) }
            encoder.run(inputs).use { result ->
                val hidden = result.get(HIDDEN_STATE).orElse(null) as? OnnxTensor ?: unsupported("no $HIDDEN_STATE")
                val values = floats(hidden, HIDDEN_STATE)
                return values to values.size / ids.size
            }
        } finally {
            tensors.forEach(OnnxTensor::close)
        }
    }

    private fun longTensor(
        values: LongArray,
        shape: LongArray,
    ): OnnxTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(values), shape)

    private fun floatTensor(
        values: FloatArray,
        shape: LongArray,
    ): OnnxTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(values), shape)

    private fun floats(
        tensor: OnnxTensor,
        name: String,
    ): FloatArray {
        val buffer = tensor.floatBuffer ?: unsupported("$name is not a float tensor")
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    /**
     * The decoder steps of one search, the beams as the batch: the encoder's state repeated per beam, the decoder's
     * key/value cache kept as arrays between the steps (reordered with the beams), and the cross-attention cache of
     * the first step, which the merged decoder's cache branch does not compute again (it returns placeholders there).
     */
    private inner class Decoding(
        sourceLength: Int,
        hidden: FloatArray,
        width: Int,
    ) : BeamStep,
        AutoCloseable {
        private val beams = config.numBeams
        private val heads = config.heads.toLong()
        private val headDim = config.headDim.toLong()
        private val layers = config.decoderLayers
        private val held = ArrayList<OnnxTensor>()
        private val encoderStates: OnnxTensor
        private val encoderMask: OnnxTensor?
        private var encoderCache: List<OnnxTensor>? = null
        private var selfCache: Array<FloatArray> = emptyArray()
        private var cacheLength = 0L

        init {
            val repeated = FloatArray(hidden.size * beams)
            for (beam in 0 until beams) hidden.copyInto(repeated, beam * hidden.size)
            encoderStates =
                floatTensor(
                    repeated,
                    longArrayOf(beams.toLong(), sourceLength.toLong(), width.toLong()),
                ).also(held::add)
            encoderMask =
                try {
                    decoderMaskName?.let {
                        longTensor(
                            LongArray(sourceLength * beams) { 1 },
                            longArrayOf(beams.toLong(), sourceLength.toLong()),
                        ).also(held::add)
                    }
                } catch (e: Throwable) {
                    close()
                    throw e
                }
        }

        override fun next(lastTokens: IntArray): Array<FloatArray> {
            val step = ArrayList<OnnxTensor>()
            try {
                val first = encoderCache == null
                val inputs = HashMap<String, OnnxTensor>()
                inputs[INPUT_IDS] =
                    longTensor(
                        LongArray(beams) { lastTokens[it].toLong() },
                        longArrayOf(beams.toLong(), 1),
                    ).also(step::add)
                inputs[ENCODER_HIDDEN_STATES] = encoderStates
                decoderMaskName?.let { name -> encoderMask?.let { inputs[name] = it } }
                inputs[USE_CACHE] = OnnxTensor.createTensor(env, booleanArrayOf(!first)).also(step::add)
                val empty = longArrayOf(beams.toLong(), heads, 0, headDim)
                for (layer in 0 until layers) {
                    for ((index, part) in listOf("key", "value").withIndex()) {
                        val self =
                            if (first) {
                                floatTensor(FloatArray(0), empty)
                            } else {
                                floatTensor(
                                    selfCache[layer * 2 + index],
                                    longArrayOf(beams.toLong(), heads, cacheLength, headDim),
                                )
                            }
                        inputs["past_key_values.$layer.decoder.$part"] = self.also(step::add)
                        inputs["past_key_values.$layer.encoder.$part"] =
                            encoderCache?.get(layer * 2 + index) ?: floatTensor(FloatArray(0), empty).also(step::add)
                    }
                }
                decoder.run(inputs).use { result ->
                    fun output(name: String) =
                        result.get(name).orElse(null) as? OnnxTensor ?: unsupported("no $name output")
                    // The whole cache is copied out and back in every step (O(T²) per sentence); cheap at sentence lengths.
                    val nextCache =
                        Array(layers * 2) { floats(output("present.${it / 2}.decoder.${part(it)}"), "present") }
                    cacheLength = (output("present.0.decoder.key").info as TensorInfo).shape[2]
                    selfCache = nextCache
                    if (first) {
                        val cross = ArrayList<OnnxTensor>()
                        for (i in 0 until layers * 2) {
                            val tensor = output("present.${i / 2}.encoder.${part(i)}")
                            val shape = (tensor.info as TensorInfo).shape
                            cross.add(floatTensor(floats(tensor, "present"), shape).also(held::add))
                        }
                        encoderCache = cross
                    }
                    return rows(floats(output(LOGITS), LOGITS), (output(LOGITS).info as TensorInfo).shape)
                }
            } finally {
                step.forEach(OnnxTensor::close)
            }
        }

        override fun reorder(sources: IntArray) {
            if (sources.withIndex().all { (i, source) -> i == source }) return
            selfCache = Array(selfCache.size) { reorderBeams(selfCache[it], sources) }
        }

        override fun close() = held.forEach(OnnxTensor::close)

        private fun part(index: Int) = if (index % 2 == 0) "key" else "value"

        /** The logits of the last position per beam from `[B, T, V]`. */
        private fun rows(
            logits: FloatArray,
            shape: LongArray,
        ): Array<FloatArray> {
            if (shape.size != 3 || shape[0] != beams.toLong()) unsupported("logits of shape ${shape.contentToString()}")
            return lastPositions(logits, beams, shape[1].toInt(), shape[2].toInt())
        }
    }

    private companion object {
        const val INPUT_IDS = "input_ids"
        const val ATTENTION_MASK = "attention_mask"
        const val HIDDEN_STATE = "last_hidden_state"
        const val ENCODER_HIDDEN_STATES = "encoder_hidden_states"
        const val ENCODER_ATTENTION_MASK = "encoder_attention_mask"
        const val USE_CACHE = "use_cache_branch"
        const val LOGITS = "logits"
    }
}
