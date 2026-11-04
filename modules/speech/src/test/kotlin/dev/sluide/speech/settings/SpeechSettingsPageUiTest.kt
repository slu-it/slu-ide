package dev.sluide.speech.settings

import dev.sluide.settings.SettingsStore
import dev.sluide.speech.install.InstallState
import dev.sluide.speech.install.KokoroVariant
import dev.sluide.speech.install.KokoroVoicesFile
import dev.sluide.speech.install.ModelInstaller
import dev.sluide.speech.install.ModelStore
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The real Speech page: its field, radio buttons and buttons are wired to the model and the installer, which only a
 * created page shows; the rules are in [SpeechSettingsPageModelTest] and `ModelInstallerTest`. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class SpeechSettingsPageUiTest {
    @TempDir
    lateinit var dir: Path

    private val settings by lazy { SpeechSettings(SettingsStore(dir.resolve("settings.properties"))) }
    private val cancelSeen = CountDownLatch(1)
    private val asked = CopyOnWriteArrayList<String>()
    private val store by lazy { ModelStore(dir.resolve("models")) }

    private fun sparse(
        file: Path,
        bytes: Long,
    ) = RandomAccessFile(file.toFile(), "rw").use { it.setLength(bytes) }

    private val installer by lazy {
        Files.createDirectories(store.root)
        sparse(store.voicesFile(), KokoroVoicesFile.BYTES)
        sparse(store.modelFile(KokoroVariant.FP16), KokoroVariant.FP16.bytes)
        ModelInstaller(
            store,
            { _, _, cancelled ->
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!cancelled() && System.nanoTime() < deadline) Thread.sleep(10)
                if (cancelled()) {
                    cancelSeen.countDown()
                    throw CancellationException()
                }
            },
            { Thread(it).apply { isDaemon = true }.start() },
            Platform::runLater,
        )
    }
    private val page by lazy {
        SpeechSettingsPage(
            settings,
            installer,
            object : SpeechSettingsDialogs {
                override fun confirmDelete(question: String): Boolean {
                    asked += question
                    return true
                }
            },
        )
    }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    private fun awaitState(
        robot: FxRobot,
        v: KokoroVariant,
        expected: InstallState,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var current: InstallState? = null
        while (System.nanoTime() < deadline) {
            robot.interact { current = installer.state(v).get() }
            if (current == expected) return
            Thread.sleep(20)
        }
        fail("state of $v is $current, not $expected")
    }

    @Test
    fun `the field and the radio buttons reach the settings`(robot: FxRobot) {
        robot.interact {
            page.node
            page.load()
            page.espeak.text = "/usr/bin/espeak-ng"
            page.radios.getValue(KokoroVariant.INT8).fire()
            page.apply()
        }
        assertEquals("/usr/bin/espeak-ng", settings.currentEspeakPath())
        assertEquals(KokoroVariant.INT8, settings.currentVariant())
    }

    @Test
    fun `the download button starts a download and becomes Cancel, which stops it`(robot: FxRobot) {
        var label = ""
        robot.interact {
            page.node
            page.downloadButtons.getValue(KokoroVariant.FP32).fire()
            label = page.downloadButtons.getValue(KokoroVariant.FP32).text
        }
        assertEquals(InstallState.Downloading(0.0), installer.state(KokoroVariant.FP32).get())
        assertEquals("Cancel", label)

        robot.interact { page.downloadButtons.getValue(KokoroVariant.FP32).fire() }

        assertTrue(cancelSeen.await(10, TimeUnit.SECONDS), "the cancel reached the download")
        awaitState(robot, KokoroVariant.FP32, InstallState.NotInstalled)
    }

    @Test
    fun `the delete button asks and then deletes the model`(robot: FxRobot) {
        robot.interact {
            page.node
            page.deleteButtons.getValue(KokoroVariant.FP16).fire()
        }
        assertEquals(1, asked.size)
        assertTrue(asked.single().startsWith("Delete the Half precision (fp16) voice model?"), asked.toString())
        awaitState(robot, KokoroVariant.FP16, InstallState.NotInstalled)
    }
}
