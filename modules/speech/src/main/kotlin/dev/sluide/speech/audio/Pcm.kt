package dev.sluide.speech.audio

import kotlin.math.roundToInt

/** Conversions between float samples (-1..1) and 16-bit signed PCM. NaN is silence, other values are clamped. */
internal object Pcm {
    /** [sample] as a 16-bit value. */
    fun toShort(sample: Float): Short =
        if (sample.isNaN()) 0 else (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()

    /** [samples] as 16-bit values: half the memory of floats, for audio kept until it plays. */
    fun toShorts(samples: FloatArray): ShortArray = ShortArray(samples.size) { toShort(samples[it]) }

    /** [pcm] from [from] to [to] (exclusive) as floats; [toShort] turns them back into the same values. */
    fun toFloats(
        pcm: ShortArray,
        from: Int,
        to: Int,
    ): FloatArray = FloatArray(to - from) { pcm[from + it].toFloat() / Short.MAX_VALUE }

    /** [samples] as 16-bit signed little-endian bytes, two per sample. */
    fun toLittleEndianBytes(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val value = toShort(samples[i]).toInt()
            bytes[2 * i] = value.toByte()
            bytes[2 * i + 1] = (value shr 8).toByte()
        }
        return bytes
    }
}
