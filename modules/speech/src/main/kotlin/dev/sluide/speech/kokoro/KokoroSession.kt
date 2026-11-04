package dev.sluide.speech.kokoro

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.sluide.speech.engine.SpeechException
import tools.jackson.databind.json.JsonMapper
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Path

private val log = System.getLogger(KokoroSession::class.java.name)

/** One model call: [run] returns the waveform and the per-token durations. */
internal interface KokoroModel : AutoCloseable {
    @Throws(ai.onnxruntime.OrtException::class)
    fun run(
        ids: LongArray,
        style: FloatArray,
        speed: Float,
    ): Pair<FloatArray, FloatArray>
}

/**
 * A loaded Kokoro ONNX model (CPU). Not thread-safe by contract; [KokoroEngine] serializes the calls. Loading and
 * running block and call native code: use platform threads, not virtual ones (they would be pinned).
 */
internal class KokoroSession(
    modelFile: Path,
) : KokoroModel {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val idsName: String
    private val durationName: String

    init {
        val options = OrtSession.SessionOptions()
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        session = env.createSession(modelFile.toString(), options)
        try {
            val inputs = session.inputNames
            val outputs = session.outputNames
            idsName =
                listOf("input_ids", "tokens").firstOrNull { it in inputs }
                    ?: throw SpeechException("Unsupported Kokoro model: no input_ids input")
            for (name in listOf("style", "speed")) {
                if (name !in inputs) throw SpeechException("Unsupported Kokoro model: no $name input")
            }
            if ("waveform" !in outputs) throw SpeechException("Unsupported Kokoro model: no waveform output")
            durationName =
                listOf("duration", "durations").firstOrNull { it in outputs }
                    ?: throw SpeechException("Unsupported Kokoro model: no duration output")
            checkVocab()
        } catch (e: Throwable) {
            session.close()
            throw e
        }
    }

    private fun checkVocab() {
        try {
            val config = session.metadata.customMetadata["kokoro_config"] ?: return
            val vocab =
                JsonMapper
                    .builder()
                    .build()
                    .readTree(config)
                    .get("vocab") ?: return
            val entries = HashMap<String, Long>()
            for (entry in vocab.properties()) entries[entry.key] = entry.value.asLong()
            if (!KokoroVocab.load().matches(entries)) {
                log.log(System.Logger.Level.WARNING, "The vocabulary of the Kokoro model differs from the bundled one")
            }
        } catch (e: Exception) {
            log.log(System.Logger.Level.WARNING, "The Kokoro model's vocabulary cannot be checked: ${e.message}")
        }
    }

    override fun run(
        ids: LongArray,
        style: FloatArray,
        speed: Float,
    ): Pair<FloatArray, FloatArray> {
        val padded = LongArray(ids.size + 2)
        ids.copyInto(padded, 1)
        OnnxTensor.createTensor(env, LongBuffer.wrap(padded), longArrayOf(1, padded.size.toLong())).use { idsTensor ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, style.size.toLong())).use { styleT ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)).use { speedT ->
                    val inputs = mapOf(idsName to idsTensor, "style" to styleT, "speed" to speedT)
                    session.run(inputs).use { result ->
                        val waveform = flatten(result.get("waveform").get().value)
                        val durations = flatten(result.get(durationName).get().value)
                        return waveform to durations
                    }
                }
            }
        }
    }

    override fun close() = session.close()

    private fun flatten(value: Any?): FloatArray =
        when (value) {
            is FloatArray -> value
            is LongArray -> FloatArray(value.size) { value[it].toFloat() }
            is IntArray -> FloatArray(value.size) { value[it].toFloat() }
            is DoubleArray -> FloatArray(value.size) { value[it].toFloat() }
            is ShortArray -> FloatArray(value.size) { value[it].toFloat() }
            is Array<*> -> value.flatMap { flatten(it).asList() }.toFloatArray()
            else -> throw SpeechException("Unexpected Kokoro output")
        }
}
