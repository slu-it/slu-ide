package dev.sluide.speech.settings

import dev.sluide.settings.SettingsStore
import dev.sluide.speech.install.KokoroVariant
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val file get() = dir.resolve("settings.properties")

    private fun settings() = SpeechSettings(SettingsStore(file))

    @Test
    fun `defaults are an automatic path and fp32`() {
        val s = settings()
        assertEquals("", s.espeakPath.get())
        assertEquals(KokoroVariant.FP32, s.variant.get())
        assertEquals(KokoroVariant.FP32, s.currentVariant())
    }

    @Test
    fun `an unknown variant counts as fp32`() {
        file.writeText("speech.kokoro.variant=HUGE\n")
        assertEquals(KokoroVariant.FP32, settings().currentVariant())
    }

    @Test
    fun `applied values are trimmed, visible at once and stored under their keys`() {
        val s = settings()
        s.apply("  /opt/bin/espeak-ng ", KokoroVariant.INT8)

        assertEquals("/opt/bin/espeak-ng", s.currentEspeakPath())
        assertEquals(KokoroVariant.INT8, s.variant.get())
        val stored = SettingsStore(file).load()
        assertEquals("/opt/bin/espeak-ng", stored["speech.espeak"])
        assertEquals("INT8", stored["speech.kokoro.variant"])
        assertEquals(KokoroVariant.INT8, settings().currentVariant())
    }
}
