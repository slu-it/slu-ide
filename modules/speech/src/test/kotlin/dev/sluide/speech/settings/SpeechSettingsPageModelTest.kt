package dev.sluide.speech.settings

import dev.sluide.settings.SettingsStore
import dev.sluide.speech.install.InstallState
import dev.sluide.speech.install.KokoroVariant
import dev.sluide.speech.install.KokoroVoicesFile
import dev.sluide.speech.install.ModelInstaller
import dev.sluide.speech.install.ModelStore
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechSettingsPageModelTest {
    @TempDir
    lateinit var dir: Path

    private var answer = false
    private val asked = mutableListOf<String>()
    private val dialogs =
        object : SpeechSettingsDialogs {
            override fun confirmDelete(question: String): Boolean {
                asked += question
                return answer
            }
        }
    private val settings by lazy { SpeechSettings(SettingsStore(dir.resolve("settings.properties"))) }
    private val installer by lazy {
        val store = ModelStore(dir.resolve("models"))
        Files.createDirectories(store.root)
        RandomAccessFile(store.voicesFile().toFile(), "rw").use { it.setLength(KokoroVoicesFile.BYTES) }
        RandomAccessFile(
            store.modelFile(KokoroVariant.FP32).toFile(),
            "rw",
        ).use { it.setLength(KokoroVariant.FP32.bytes) }
        ModelInstaller(store, { _, _, _ -> }, { it.run() }, { it.run() })
    }
    private val model by lazy { SpeechSettingsPageModel(settings, installer, dialogs) }

    @Test
    fun `path and variant stay a draft until applied, and loading drops them`() {
        model.espeak.set("/x/espeak-ng")
        model.variant.set(KokoroVariant.FP16)
        assertEquals("", settings.espeakPath.get())

        model.load()
        assertEquals("", model.espeak.get())
        assertEquals(KokoroVariant.FP32, model.variant.get())

        model.espeak.set("/x/espeak-ng")
        model.variant.set(KokoroVariant.FP16)
        model.apply()
        assertEquals("/x/espeak-ng", settings.espeakPath.get())
        assertEquals(KokoroVariant.FP16, settings.variant.get())
    }

    @Test
    fun `delete asks first, Cancel keeps the model and Delete removes it`() {
        assertEquals(InstallState.Installed, installer.state(KokoroVariant.FP32).get())

        model.delete(KokoroVariant.FP32)
        assertEquals(
            listOf(
                "Delete the Full quality (fp32) voice model? It must be downloaded again (326 MB) to read aloud with it.",
            ),
            asked,
        )
        assertEquals(InstallState.Installed, installer.state(KokoroVariant.FP32).get())

        answer = true
        model.delete(KokoroVariant.FP32)
        assertEquals(2, asked.size)
        assertEquals(InstallState.NotInstalled, installer.state(KokoroVariant.FP32).get())
    }

    @Test
    fun `status texts`() {
        assertEquals("Not installed", SpeechSettingsPageModel.statusText(InstallState.NotInstalled))
        assertEquals("Installed", SpeechSettingsPageModel.statusText(InstallState.Installed))
        assertEquals("40 %", SpeechSettingsPageModel.statusText(InstallState.Downloading(0.4)))
        assertEquals("boom", SpeechSettingsPageModel.statusText(InstallState.Failed("boom")))
        assertEquals("Small (int8) · 114 MB", SpeechSettingsPageModel.rowLabel(KokoroVariant.INT8))
    }
}
