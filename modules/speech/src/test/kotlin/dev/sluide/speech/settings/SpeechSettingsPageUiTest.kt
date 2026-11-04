package dev.sluide.speech.settings

import dev.sluide.settings.SettingsStore
import dev.sluide.speech.install.InstallState
import dev.sluide.speech.install.KokoroVariant
import dev.sluide.speech.install.ModelInstaller
import dev.sluide.speech.install.ModelStore
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The real Speech page: its field, radio buttons and Download button are wired to the model and the installer, which
 * only a created page shows; the rules are in [SpeechSettingsPageModelTest] and `ModelInstallerTest`. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class SpeechSettingsPageUiTest {
    @TempDir
    lateinit var dir: Path

    private val settings by lazy { SpeechSettings(SettingsStore(dir.resolve("settings.properties"))) }
    private val downloads = mutableListOf<KokoroVariant>()
    private val installer by lazy {
        // Never finishes: the executor drops the work, so the state stays at Downloading.
        ModelInstaller(
            ModelStore(dir.resolve("models")),
            { v, _, _ -> downloads += v },
            { },
            { it.run() },
        )
    }
    private val page by lazy {
        SpeechSettingsPage(
            settings,
            installer,
            object : SpeechSettingsDialogs {
                override fun confirmDelete(question: String) = false
            },
        )
    }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    @Test
    fun `the field, the radio buttons and the download button reach settings and installer`(robot: FxRobot) {
        robot.interact {
            page.node
            page.load()
            page.espeak.text = "/usr/bin/espeak-ng"
            page.radios.getValue(KokoroVariant.INT8).fire()
            page.apply()
            page.downloadButtons.getValue(KokoroVariant.FP16).fire()
        }
        assertEquals("/usr/bin/espeak-ng", settings.currentEspeakPath())
        assertEquals(KokoroVariant.INT8, settings.currentVariant())
        assertEquals(InstallState.Downloading(0.0), installer.state(KokoroVariant.FP16).get())
        assertEquals("Cancel", page.downloadButtons.getValue(KokoroVariant.FP16).text)
    }
}
