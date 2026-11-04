package dev.sluide.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Writes a 16-bit PCM mono sine WAV of [seconds] to [file], header written by hand (no `javax.sound`). */
fun writeSineWav(
    file: Path,
    seconds: Double = 1.0,
    sampleRate: Int = 44_100,
    frequency: Double = 440.0,
): Path {
    val samples = (seconds * sampleRate).roundToInt()
    val dataSize = samples * 2
    val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
    buffer
        .put("fmt ".toByteArray())
        .putInt(16) // size of the fmt chunk
        .putShort(1) // PCM
        .putShort(1) // mono
        .putInt(sampleRate)
        .putInt(sampleRate * 2) // byte rate
        .putShort(2) // block align
        .putShort(16) // bits per sample
    buffer.put("data".toByteArray()).putInt(dataSize)
    repeat(samples) { i ->
        buffer.putShort((sin(2 * PI * frequency * i / sampleRate) * Short.MAX_VALUE * 0.5).toInt().toShort())
    }
    return Files.write(file, buffer.array())
}
