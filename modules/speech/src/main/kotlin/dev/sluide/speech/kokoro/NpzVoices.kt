package dev.sluide.speech.kokoro

import dev.sluide.speech.engine.SpeechException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.zip.ZipFile

/** Where the model's style vector comes from; [NpzVoices] reads it from the voices file. */
internal fun interface StyleSource {
    /** The style row for a piece of [tokenCount] tokens (without pads) spoken by [voiceId]. */
    @Throws(SpeechException::class)
    fun style(
        voiceId: String,
        tokenCount: Int,
    ): FloatArray
}

/**
 * The voices file of kokoro-onnx: an `.npz` (a zip) with one `<voice>.npy` per voice, `<f4`, shape (510, 1, 256), one
 * style vector per token count. A voice is read on first use; the last few are kept. Thread-safe.
 */
internal class NpzVoices(
    private val file: Path,
) : StyleSource {
    private val cache =
        object : LinkedHashMap<String, FloatArray>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>) = size > CACHED_VOICES
        }

    @Throws(SpeechException::class)
    override fun style(
        voiceId: String,
        tokenCount: Int,
    ): FloatArray {
        val data = synchronized(cache) { cache.getOrPut(voiceId) { read(voiceId) } }
        val row = (minOf(tokenCount, ROWS) - 1).coerceAtLeast(0)
        return data.copyOfRange(row * WIDTH, (row + 1) * WIDTH)
    }

    private fun read(voiceId: String): FloatArray {
        val bytes =
            try {
                ZipFile(file.toFile()).use { zip ->
                    val entry = zip.getEntry("$voiceId.npy") ?: throw SpeechException("Unknown Kokoro voice: $voiceId")
                    zip.getInputStream(entry).use { it.readAllBytes() }
                }
            } catch (e: IOException) {
                throw SpeechException("The Kokoro voices file cannot be read: ${e.message}", e)
            }
        return parse(bytes)
    }

    private fun parse(bytes: ByteArray): FloatArray {
        val bad = SpeechException("Unexpected Kokoro voice data")
        if (bytes.size < 10 || bytes[0] != 0x93.toByte() || String(bytes, 1, 5, Charsets.US_ASCII) != "NUMPY") throw bad
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val headerStart: Int
        val headerLength: Int
        when (bytes[6].toInt()) {
            1 -> {
                headerStart = 10
                headerLength = buffer.getShort(8).toInt() and 0xFFFF
            }

            2 -> {
                if (bytes.size < 12) throw bad
                headerStart = 12
                headerLength = buffer.getInt(8)
            }

            else -> {
                throw bad
            }
        }
        if (headerLength < 0 || headerStart + headerLength > bytes.size) throw bad
        val header = String(bytes, headerStart, headerLength, Charsets.US_ASCII)
        if (!DESCR.containsMatchIn(header) || !FORTRAN.containsMatchIn(header) || !SHAPE.containsMatchIn(header)) {
            throw bad
        }
        val dataStart = headerStart + headerLength
        if (bytes.size - dataStart != ROWS * WIDTH * 4) throw bad
        val floats = FloatArray(ROWS * WIDTH)
        buffer.position(dataStart)
        buffer.asFloatBuffer().get(floats)
        return floats
    }

    private companion object {
        const val ROWS = 510
        const val WIDTH = 256
        const val CACHED_VOICES = 4
        val DESCR = Regex("""'descr'\s*:\s*'<f4'""")
        val FORTRAN = Regex("""'fortran_order'\s*:\s*False""")
        val SHAPE = Regex("""'shape'\s*:\s*\(\s*510\s*,\s*1\s*,\s*256\s*,?\s*\)""")
    }
}
