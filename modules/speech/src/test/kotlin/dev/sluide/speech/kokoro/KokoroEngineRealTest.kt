package dev.sluide.speech.kokoro

import dev.sluide.settings.SettingsStore
import dev.sluide.speech.engine.SpeechChunk
import dev.sluide.speech.install.KokoroVariant
import dev.sluide.speech.install.ModelStore
import dev.sluide.speech.settings.SpeechSettings
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real model; skipped unless `SLUIDE_KOKORO_SMOKE=1` is set, the model files are in
 * `~/.sluide/models/kokoro-v1.0/` (or the folder in `SLUIDE_KOKORO_MODEL_DIR`) and espeak-ng is found. `SLUIDE_KOKORO_WAV` names a file to write the audio to.
 */
class KokoroEngineRealTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `speaks a sentence with plausible audio and word timings`() {
        assumeTrue(System.getenv("SLUIDE_KOKORO_SMOKE") == "1", "set SLUIDE_KOKORO_SMOKE=1 to run the real model")
        val modelDir =
            System.getenv("SLUIDE_KOKORO_MODEL_DIR")?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".sluide", "models", "kokoro-v1.0")
        val store = ModelStore(modelDir)
        assumeTrue(store.installed(KokoroVariant.FP32), "Kokoro model files not found in $modelDir")
        val engine = KokoroEngine(SpeechSettings(SettingsStore(dir.resolve("settings.properties"))), store)
        assumeTrue(engine.problems().isEmpty(), "espeak-ng not found")
        try {
            val text = "Hello world. This is Thoreau reading aloud."
            val chunk = SpeechChunk(0, text.length, 0, text.length, text)
            val voice = engine.voices.first()

            val started = System.nanoTime()
            val first = engine.synthesize(chunk, voice, 1.0)
            val firstSeconds = (System.nanoTime() - started) / 1e9
            val started2 = System.nanoTime()
            val result = engine.synthesize(chunk, voice, 1.0)
            val secondSeconds = (System.nanoTime() - started2) / 1e9

            val audioSeconds = result.samples.size / result.sampleRate.toDouble()
            println(
                "kokoro: audio %.2f s, first call %.2f s (with model load), second call %.2f s, RTF %.2f"
                    .format(audioSeconds, firstSeconds, secondSeconds, secondSeconds / audioSeconds),
            )
            assertEquals(first.samples.size, result.samples.size)
            assertTrue(audioSeconds in 1.0..6.0, "length $audioSeconds s")
            assertTrue(result.samples.any { kotlin.math.abs(it) > 0.01f }, "silent")
            assertEquals(7, result.words.size)
            assertTrue(result.words.all { it.startSample <= it.endSample })
            assertTrue(result.words.zipWithNext().all { (a, b) -> a.endSample <= b.startSample })
            assertTrue(result.words.last().endSample > result.samples.size * 0.8)
            val timed = result.words.joinToString { "${text.substring(it.start, it.end)}@${it.startSample}" }
            println("kokoro words: $timed")
            System.getenv("SLUIDE_KOKORO_WAV")?.let { Files.write(Path.of(it), wav(result.samples, result.sampleRate)) }
        } finally {
            engine.destroy()
        }
    }

    private fun wav(
        samples: FloatArray,
        rate: Int,
    ): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVEfmt ".toByteArray())
        header
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(rate)
            .putInt(rate * 2)
            .putShort(2)
            .putShort(16)
        header.put("data".toByteArray()).putInt(data.capacity())
        return ByteArrayOutputStream().apply { write(header.array()) }.also { it.write(data.array()) }.toByteArray()
    }
}
